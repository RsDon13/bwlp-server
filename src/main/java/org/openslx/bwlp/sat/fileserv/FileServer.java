package org.openslx.bwlp.sat.fileserv;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.bwlp.sat.database.mappers.DbImage;
import org.openslx.bwlp.sat.database.models.LocalImageVersion;
import org.openslx.bwlp.sat.util.Constants;
import org.openslx.bwlp.sat.util.FileSystem;
import org.openslx.bwlp.sat.util.Formatter;
import org.openslx.bwlp.sat.util.Identity;
import org.openslx.bwlp.thrift.iface.ImageDetailsRead;
import org.openslx.bwlp.thrift.iface.TTransferRejectedException;
import org.openslx.bwlp.thrift.iface.UserInfo;
import org.openslx.filetransfer.Downloader;
import org.openslx.filetransfer.IncomingEvent;
import org.openslx.filetransfer.Listener;
import org.openslx.filetransfer.Uploader;
import org.openslx.thrifthelper.Comparators;
import org.openslx.util.GrowingThreadPoolExecutor;
import org.openslx.util.PrioThreadFactory;

public class FileServer implements IncomingEvent {

	private static final Logger LOGGER = LogManager.getLogger(FileServer.class);

	/**
	 * Listener for incoming unencrypted connections
	 */
	private final Listener plainListener = new Listener(this, null, 9092, Constants.TRANSFER_TIMEOUT); // TODO: Config

	private final Listener sslListener;

	private final ExecutorService transferPool = new GrowingThreadPoolExecutor(1, Constants.MAX_UPLOADS
			+ Constants.MAX_DOWNLOADS, 1, TimeUnit.MINUTES, new SynchronousQueue<Runnable>(),
			new PrioThreadFactory("ClientTransferPool", Thread.NORM_PRIORITY - 2));

	/**
	 * All currently running uploads, indexed by token
	 */
	private final Map<String, IncomingDataTransfer> uploads = new ConcurrentHashMap<>();

	/**
	 * All currently running downloads, indexed by token
	 */
	private final Map<String, OutgoingDataTransfer> downloads = new ConcurrentHashMap<>();

	private static final FileServer globalInstance = new FileServer();

	private FileServer() {
		SSLContext ctx = Identity.getSSLContext();
		sslListener = ctx == null ? null : new Listener(this, ctx, 9093, Constants.TRANSFER_TIMEOUT);
		LOGGER.info("Max allowed concurrent uploads from clients: " + Constants.MAX_UPLOADS);
		LOGGER.info("Max allowed concurrent downloads from clients: " + Constants.MAX_DOWNLOADS);
		LOGGER.info("Max allowed connections per transfer: " + Constants.MAX_CONNECTIONS_PER_TRANSFER);
	}

	public static FileServer instance() {
		return globalInstance;
	}

	public boolean start() {
		boolean ret = plainListener.start();
		if (sslListener != null) {
			ret |= sslListener.start();
		}
		return ret;
	}

	@Override
	public void incomingDownloadRequest(Uploader uploader) throws IOException {
		String token = uploader.getToken();
		OutgoingDataTransfer download = downloads.get(token);
		if (download == null) {
			LOGGER.warn("Download request: Unknown token " + token);
			uploader.cancel();
			return;
		}
		if (!download.addConnection(uploader, transferPool)) {
			uploader.cancel();
		}
	}

	@Override
	public void incomingUploadRequest(Downloader downloader) throws IOException {
		String token = downloader.getToken();
		IncomingDataTransfer upload = uploads.get(token);
		if (upload == null) {
			LOGGER.warn("Upload request: Unknown token " + token);
			downloader.cancel();
			return;
		}
		if (!upload.addConnection(downloader, transferPool)) {
			if (upload.getErrorMessage() != null) {
				downloader.sendErrorCode(upload.getErrorMessage());
			}
			downloader.cancel();
		}
	}

	/**
	 * Get an upload instance by given token.
	 * 
	 * @param uploadToken
	 * @return
	 */
	public IncomingDataTransfer getUploadByToken(String uploadToken) {
		if (uploadToken == null)
			return null;
		return uploads.get(uploadToken);
	}

	public OutgoingDataTransfer getDownloadByToken(String downloadToken) {
		if (downloadToken == null)
			return null;
		return downloads.get(downloadToken);
	}

	public IncomingDataTransfer createNewUserUpload(UserInfo owner, ImageDetailsRead image, long fileSize,
			List<byte[]> sha1Sums, byte[] machineDescription) throws TTransferRejectedException {
		Iterator<IncomingDataTransfer> it = uploads.values().iterator();
		final long now = System.currentTimeMillis();
		int activeUploads = 0;
		int activeUserUploads = 0;
		while (it.hasNext()) {
			IncomingDataTransfer upload = it.next();
			if (upload.isComplete(now) || upload.hasReachedIdleTimeout(now)) {
				upload.cancel();
				it.remove();
				continue;
			}
			if (upload.countsTowardsConnectionLimit(now)) {
				if (upload.getOwner() != null && Comparators.user.compare(owner, upload.getOwner()) == 0) {
					activeUserUploads += 1;
				}
				activeUploads += 1;
			}
		}
		if (activeUploads >= Constants.MAX_UPLOADS || activeUserUploads > Constants.MAX_UPLOADS_PER_USER) {
			throw new TTransferRejectedException("Server busy. Too many running uploads (User: "
					+ activeUserUploads + "/" + Constants.MAX_UPLOADS_PER_USER + "; Total: " + activeUploads
					+ "/" + Constants.MAX_UPLOADS + ").");
		}
		File destinationFile = null;
		do {
			destinationFile = Formatter.getTempImageName();
		} while (destinationFile.exists());
		destinationFile.getParentFile().mkdirs();

		String key = UUID.randomUUID().toString();
		IncomingDataTransfer upload;
		try {
			upload = new IncomingDataTransfer(key, owner, image, destinationFile, fileSize, sha1Sums,
					machineDescription, false);
		} catch (FileNotFoundException e) {
			LOGGER.error("Could not open destination file for writing", e);
			throw new TTransferRejectedException("Destination file not writable!");
		}

		uploads.put(key, upload);
		return upload;
	}

	public int getPlainPort() {
		if (plainListener == null)
			return 0;
		return plainListener.getPort();
	}

	public int getSslPort() {
		if (sslListener == null)
			return 0;
		return sslListener.getPort();
	}

	public OutgoingDataTransfer createNewUserDownload(LocalImageVersion localImageData)
			throws TTransferRejectedException {
		Iterator<OutgoingDataTransfer> it = downloads.values().iterator();
		final long now = System.currentTimeMillis();
		int activeDownloads = 0;
		while (it.hasNext()) {
			OutgoingDataTransfer download = it.next();
			if (download.isComplete(now) || download.hasReachedIdleTimeout(now)) {
				download.cancel();
				it.remove();
				continue;
			}
			if (download.countsTowardsConnectionLimit(now)) {
				activeDownloads += 1;
			}
		}
		if (activeDownloads >= Constants.MAX_DOWNLOADS) {
			throw new TTransferRejectedException("Server busy. Too many running uploads (" + activeDownloads
					+ "/" + Constants.MAX_UPLOADS + ").");
		}
		// Determine src file and go
		File srcFile = FileSystem.composeAbsoluteImagePath(localImageData);
		String errorMessage = null;
		if (srcFile == null) {
			LOGGER.warn("Rejecting download of VID " + localImageData.imageVersionId
					+ ": Invalid local relative path");
			errorMessage = "File has invalid path on server";
		} else {
			if (!srcFile.canRead()) {
				LOGGER.warn("Rejecting download of VID " + localImageData.imageVersionId + ": Missing "
						+ srcFile.getPath());
				errorMessage = "File missing on server";
			}
			if (srcFile.length() != localImageData.fileSize) {
				LOGGER.warn("Rejecting download of VID " + localImageData.imageVersionId
						+ ": Size mismatch for " + srcFile.getPath() + " (expected "
						+ localImageData.fileSize + ", is " + srcFile.length() + ")");
				errorMessage = "File corrupted on server";
			}
		}
		if (errorMessage != null) {
			if (localImageData.isValid) {
				try {
					DbImage.markValid(false, true, localImageData);
				} catch (SQLException e) {
				}
			}
			throw new TTransferRejectedException(errorMessage);
		}
		String key = UUID.randomUUID().toString();
		OutgoingDataTransfer transfer = new OutgoingDataTransfer(key, srcFile, getPlainPort(), getSslPort(), localImageData.imageVersionId);
		downloads.put(key, transfer);
		return transfer;
	}

	public Status getStatus() {
		return new Status();
	}
	
	/**
	 * Check whether the given imageVersionId refers to an active transfer.
	 */
	public boolean isActiveTransfer(String baseId, String versionId) {
		long now = System.currentTimeMillis();
		if (versionId != null) {
			for (OutgoingDataTransfer odt : downloads.values()) {
				if (versionId != null && versionId.equals(odt.getVersionId()) && !odt.isComplete(now) && odt.isActive())
					return true;
			}
		}
		for (IncomingDataTransfer idt : uploads.values()) {
			if (idt.isComplete(now) || !idt.isActive())
				continue;
			if (versionId != null && versionId.equals(idt.getVersionId()))
				return true;
			if (baseId != null && baseId.equals(idt.getBaseId()))
				return true;
		}
		return false;
	}

	class Status {
		public final int activeUploads;
		public final int activeDownloads;

		private Status() {
			long now = System.currentTimeMillis();
			int d = 0, u = 0;
			for (OutgoingDataTransfer t : downloads.values()) {
				if (t.countsTowardsConnectionLimit(now)) {
					d += 1;
				}
			}
			for (IncomingDataTransfer t : uploads.values()) {
				if (t.countsTowardsConnectionLimit(now)) {
					u += 1;
				}
			}
			this.activeDownloads = d;
			this.activeUploads = u;
		}
	}

}
