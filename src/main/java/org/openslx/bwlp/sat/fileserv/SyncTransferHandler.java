package org.openslx.bwlp.sat.fileserv;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.thrift.TException;
import org.openslx.bwlp.sat.database.mappers.DbImage;
import org.openslx.bwlp.sat.database.mappers.DbImageBlock;
import org.openslx.bwlp.sat.database.mappers.DbUser;
import org.openslx.bwlp.sat.database.models.ImageVersionMeta;
import org.openslx.bwlp.sat.database.models.LocalImageVersion;
import org.openslx.bwlp.sat.util.Constants;
import org.openslx.bwlp.sat.util.FileSystem;
import org.openslx.bwlp.sat.util.Formatter;
import org.openslx.bwlp.thrift.iface.ImageDetailsRead;
import org.openslx.bwlp.thrift.iface.ImagePublishData;
import org.openslx.bwlp.thrift.iface.ImageSummaryRead;
import org.openslx.bwlp.thrift.iface.InvocationError;
import org.openslx.bwlp.thrift.iface.TAuthorizationException;
import org.openslx.bwlp.thrift.iface.TInvocationException;
import org.openslx.bwlp.thrift.iface.TNotFoundException;
import org.openslx.bwlp.thrift.iface.TTransferRejectedException;
import org.openslx.bwlp.thrift.iface.TransferInformation;
import org.openslx.thrifthelper.ThriftManager;
import org.openslx.util.GrowingThreadPoolExecutor;
import org.openslx.util.PrioThreadFactory;
import org.openslx.util.QuickTimer;
import org.openslx.util.QuickTimer.Task;

/**
 * Manages file transfers between this satellite and the master server.
 */
public class SyncTransferHandler {

	private static final Logger LOGGER = LogManager.getLogger(SyncTransferHandler.class);

	private static final GrowingThreadPoolExecutor transferPool = new GrowingThreadPoolExecutor(1,
			Constants.MAX_MASTER_UPLOADS + Constants.MAX_MASTER_DOWNLOADS, 1, TimeUnit.MINUTES,
			new ArrayBlockingQueue<Runnable>(1), new PrioThreadFactory("MasterTransferPool",
					Thread.NORM_PRIORITY - 3));

	/**
	 * All currently running downloads from master, indexed by token, which is == versionId
	 */
	private static final Map<String, IncomingDataTransfer> downloads = new ConcurrentHashMap<>();

	/**
	 * All currently running uploads from master, indexed by token
	 */
	private static final Map<String, OutgoingDataTransfer> uploadsByTransferId = new ConcurrentHashMap<>();

	/**
	 * All currently running uploads to master, by image version id
	 */
	private static final Map<String, OutgoingDataTransfer> uploadsByVersionId = new ConcurrentHashMap<>();

	private static Task heartBeatTask = new Task() {
		private int skips = 0;
		private final Runnable worker = new Runnable() {
			@Override
			public void run() {
				final long now = System.currentTimeMillis();
				for (Iterator<IncomingDataTransfer> it = downloads.values().iterator(); it.hasNext();) {
					IncomingDataTransfer download = it.next();
					if (download.isActive())
						download.heartBeat(transferPool);
					if (download.isComplete(now)) {
						LOGGER.info("Download <" + download.getId() + "> from master server complete");
						it.remove();
					} else if (download.hasReachedIdleTimeout(now) || download.connectFailCount() > 50) {
						LOGGER.info("Download <" + download.getId() + "> errored out");
						it.remove();
					}
				}
				for (Iterator<OutgoingDataTransfer> it = uploadsByTransferId.values().iterator(); it.hasNext();) {
					OutgoingDataTransfer upload = it.next();
					if (upload.isActive())
						upload.heartBeat(transferPool);
					if (upload.isComplete(now)) {
						LOGGER.info("Upload <" + upload.getId() + "> to master server complete");
						it.remove();
					} else if (upload.hasReachedIdleTimeout(now) || upload.connectFailCount() > 50) {
						LOGGER.info("Upload <" + upload.getId() + "> errored out");
						it.remove();
					}
				}
				for (Iterator<OutgoingDataTransfer> it = uploadsByVersionId.values().iterator(); it.hasNext();) {
					OutgoingDataTransfer upload = it.next();
					if (upload.isComplete(now)) {
						it.remove();
					} else if (upload.hasReachedIdleTimeout(now) || upload.connectFailCount() > 50) {
						it.remove();
					}
				}
			}
		};

		@Override
		public synchronized void fire() {
			if (uploadsByTransferId.isEmpty() && uploadsByVersionId.isEmpty() && downloads.isEmpty())
				return; // Nothing to do anyways, don't wake up another thread
			if (transferPool.getMaximumPoolSize() - transferPool.getActiveCount() < 2 && ++skips < 10)
				return; // Quite busy, don't trigger heartbeat
			skips = 0;
			transferPool.execute(worker);
		}
	};

	//

	static {
		QuickTimer.scheduleAtFixedDelay(heartBeatTask, 123, TimeUnit.SECONDS.toMillis(56));
	}

	public synchronized static String requestImageUpload(String userToken, ImageSummaryRead imgBase,
			LocalImageVersion imgVersion) throws SQLException, TNotFoundException, TInvocationException,
			TAuthorizationException, TTransferRejectedException {
		TransferInformation transferInfo;
		OutgoingDataTransfer existing = uploadsByVersionId.get(imgVersion.imageVersionId);
		if (existing != null) {
			LOGGER.info("Client wants to upload image " + imgVersion.imageVersionId
					+ " which is already in progess via " + existing.getId());
			return existing.getId();
		}
		File absFile = FileSystem.composeAbsoluteImagePath(imgVersion);
		if (!absFile.isFile() || !absFile.canRead()) {
			LOGGER.error("Cannot upload " + imgVersion.imageVersionId + ": file missing: "
					+ absFile.getAbsolutePath());
			DbImage.markValid(false, true, imgVersion);
			throw new TInvocationException(InvocationError.INTERNAL_SERVER_ERROR, "Source file not readable");
		}
		if (absFile.length() != imgVersion.fileSize) {
			LOGGER.error("Cannot upload" + imgVersion.imageVersionId + ": wrong file size - expected "
					+ imgVersion.fileSize + ", got " + absFile.length());
			DbImage.markValid(false, true, imgVersion);
			throw new TInvocationException(InvocationError.INTERNAL_SERVER_ERROR,
					"File corrupted on satellite server");
		}
		checkUploadCount();
		ImageVersionMeta versionDetails = DbImage.getVersionDetails(imgVersion.imageVersionId);
		if (versionDetails == null || versionDetails.machineDescription == null
				|| versionDetails.machineDescription.length == 0)
			throw new TInvocationException(InvocationError.MISSING_DATA,
					"Given virtual machine has no hardware description");
		ImageDetailsRead details = DbImage.getImageDetails(null, imgVersion.imageBaseId);
		List<ByteBuffer> blockHashes = DbImageBlock.getBlockHashes(imgVersion.imageVersionId);
		ImagePublishData publishData = new ImagePublishData();
		publishData.createTime = imgVersion.createTime;
		publishData.description = details.description;
		publishData.fileSize = imgVersion.fileSize;
		publishData.imageBaseId = imgVersion.imageBaseId;
		publishData.imageName = details.imageName;
		publishData.imageVersionId = imgVersion.imageVersionId;
		publishData.isTemplate = details.isTemplate;
		publishData.osId = details.osId;
		publishData.uploader = DbUser.getOrNull(imgVersion.uploaderId);
		publishData.owner = DbUser.getOrNull(imgBase.ownerId);
		publishData.virtId = details.virtId;
		publishData.machineDescription = ByteBuffer.wrap(versionDetails.machineDescription);
		try {
			transferInfo = ThriftManager.getMasterClient().submitImage(userToken, publishData, blockHashes);
		} catch (TAuthorizationException e) {
			LOGGER.warn("Master server rejected our session on uploadImage", e);
			throw e;
		} catch (TInvocationException e) {
			LOGGER.warn("Master server made a boo-boo on uploadImage", e);
			throw e;
		} catch (TTransferRejectedException e) {
			LOGGER.warn("Master server rejected our upload request", e);
			throw e;
		} catch (TException e) {
			LOGGER.warn("Unknown exception on uploadImage to master server", e);
			throw new TInvocationException(InvocationError.INTERNAL_SERVER_ERROR,
					"Communication with master server failed");
		}
		OutgoingDataTransfer transfer = new OutgoingDataTransfer(transferInfo, absFile, imgVersion.imageVersionId);
		uploadsByVersionId.put(imgVersion.imageVersionId, transfer);
		uploadsByTransferId.put(transfer.getId(), transfer);
		LOGGER.info("Client wants to upload image " + imgVersion.imageVersionId
				+ ", created transfer " + transfer.getId());
		heartBeatTask.fire();
		return transfer.getId();
	}

	public synchronized static String requestImageDownload(String userToken, ImagePublishData image)
			throws TInvocationException, TAuthorizationException, TNotFoundException {
		TransferInformation transferInfo;
		// Already replicating this one?
		IncomingDataTransfer existing = downloads.get(image.imageVersionId);
		if (existing != null)
			return existing.getId();
		checkDownloadCount();
		try {
			transferInfo = ThriftManager.getMasterClient().downloadImage(userToken, image.imageVersionId);
		} catch (TAuthorizationException e) {
			LOGGER.warn("Master server rejected our session on downloadImage", e);
			throw e;
		} catch (TInvocationException e) {
			LOGGER.warn("Master server made a boo-boo on downloadImage", e);
			throw e;
		} catch (TNotFoundException e) {
			LOGGER.warn("Master server couldn't find image on downloadImage", e);
			throw e;
		} catch (TException e) {
			LOGGER.warn("Master server made a boo-boo on downloadImage", e);
			throw new TInvocationException(InvocationError.INTERNAL_SERVER_ERROR,
					"Communication with master server failed");
		}
		// Already exists? Already complete?
		LocalImageVersion localImageData;
		try {
			localImageData = DbImage.getLocalImageData(image.imageVersionId);
		} catch (TNotFoundException e) {
			localImageData = null;
		} catch (SQLException e) {
			throw new TInvocationException(InvocationError.INTERNAL_SERVER_ERROR, "Database error");
		}
		File tmpFile;
		if (localImageData == null) {
			// New
			tmpFile = null;
			do {
				tmpFile = Formatter.getTempImageName();
			} while (tmpFile.exists());
		} else {
			tmpFile = FileSystem.composeAbsoluteImagePath(localImageData);
		}
		tmpFile.getParentFile().mkdirs();
		try {
			IncomingDataTransfer transfer = new IncomingDataTransfer(image, tmpFile, transferInfo,
					localImageData != null);
			downloads.put(transfer.getId(), transfer);
			heartBeatTask.fire();
			return transfer.getId();
		} catch (FileNotFoundException e) {
			LOGGER.warn("Could not open " + tmpFile.getAbsolutePath());
			throw new TInvocationException(InvocationError.INTERNAL_SERVER_ERROR,
					"Could not access local file for writing");
		}
	}

	private static void checkDownloadCount() throws TInvocationException {
		Iterator<IncomingDataTransfer> it = downloads.values().iterator();
		final long now = System.currentTimeMillis();
		int activeDownloads = 0;
		while (it.hasNext()) {
			IncomingDataTransfer upload = it.next();
			if (upload.isComplete(now) || upload.hasReachedIdleTimeout(now)) {
				upload.cancel();
				it.remove();
				continue;
			}
			if (upload.countsTowardsConnectionLimit(now)) {
				activeDownloads++;
			}
		}
		if (activeDownloads >= Constants.MAX_MASTER_DOWNLOADS) {
			throw new TInvocationException(InvocationError.INTERNAL_SERVER_ERROR,
					"Server busy. Too many running downloads (" + activeDownloads + "/"
							+ Constants.MAX_MASTER_DOWNLOADS + ").");
		}
	}

	private static void checkUploadCount() throws TInvocationException {
		Iterator<OutgoingDataTransfer> it = uploadsByTransferId.values().iterator();
		final long now = System.currentTimeMillis();
		int activeUploads = 0;
		while (it.hasNext()) {
			OutgoingDataTransfer download = it.next();
			if (download.isComplete(now) || download.hasReachedIdleTimeout(now)) {
				download.cancel();
				it.remove();
				continue;
			}
			if (download.countsTowardsConnectionLimit(now)) {
				activeUploads++;
			}
		}
		if (activeUploads >= Constants.MAX_MASTER_UPLOADS) {
			throw new TInvocationException(InvocationError.INTERNAL_SERVER_ERROR,
					"Server busy. Too many running uploads (" + activeUploads + "/"
							+ Constants.MAX_MASTER_UPLOADS + ").");
		}
	}

	/**
	 * Get an upload instance by given token.
	 * 
	 * @param uploadToken
	 * @return
	 */
	public static OutgoingDataTransfer getUploadByToken(String uploadToken) {
		if (uploadToken == null)
			return null;
		return uploadsByTransferId.get(uploadToken);
	}

	public static IncomingDataTransfer getDownloadByToken(String downloadToken) {
		if (downloadToken == null)
			return null;
		return downloads.get(downloadToken);
	}
	
	/**
	 * Check whether the given imageVersionId refers to an active transfer.
	 */
	public static boolean isActiveTransfer(String baseId, String versionId) {
		if (versionId != null) {
			OutgoingDataTransfer odt = uploadsByVersionId.get(versionId);
			if (odt != null && !odt.isComplete(System.currentTimeMillis()) && odt.isActive())
				return true;
		}
		long now = System.currentTimeMillis();
		for (IncomingDataTransfer idt : downloads.values()) {
			if (idt.isComplete(now) || !idt.isActive())
				continue;
			if (versionId != null && versionId.equals(idt.getVersionId()))
				return true;
			if (baseId != null && baseId.equals(idt.getBaseId()))
				return true;
		}
		return false;
	}

}
