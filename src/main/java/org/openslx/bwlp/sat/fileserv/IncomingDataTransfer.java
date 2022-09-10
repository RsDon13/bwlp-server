package org.openslx.bwlp.sat.fileserv;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.bwlp.sat.RuntimeConfig;
import org.openslx.bwlp.sat.database.mappers.DbImage;
import org.openslx.bwlp.sat.database.mappers.DbImageBlock;
import org.openslx.bwlp.sat.database.mappers.DbLog;
import org.openslx.bwlp.sat.util.Configuration;
import org.openslx.bwlp.sat.util.Constants;
import org.openslx.bwlp.sat.util.FileSystem;
import org.openslx.bwlp.sat.util.Formatter;
import org.openslx.bwlp.thrift.iface.ImageDetailsRead;
import org.openslx.bwlp.thrift.iface.ImagePublishData;
import org.openslx.bwlp.thrift.iface.ImageVersionWrite;
import org.openslx.bwlp.thrift.iface.SscMode;
import org.openslx.bwlp.thrift.iface.TNotFoundException;
import org.openslx.bwlp.thrift.iface.TransferInformation;
import org.openslx.bwlp.thrift.iface.TransferState;
import org.openslx.bwlp.thrift.iface.UploadOptions;
import org.openslx.bwlp.thrift.iface.UserInfo;
import org.openslx.filetransfer.Downloader;
import org.openslx.filetransfer.util.ChunkStatus;
import org.openslx.filetransfer.util.FileChunk;
import org.openslx.filetransfer.util.HashChecker;
import org.openslx.filetransfer.util.IncomingTransferBase;
import org.openslx.util.ThriftUtil;
import org.openslx.virtualization.disk.DiskImage;
import org.openslx.virtualization.disk.DiskImageException;

public class IncomingDataTransfer extends IncomingTransferBase {

	private static final Logger LOGGER = LogManager.getLogger(IncomingDataTransfer.class);

	private static final long MIN_FREE_SPACE_BYTES = FileChunk.CHUNK_SIZE * (2 + Constants.MAX_UPLOADS);

	/**
	 * User owning this uploaded file.
	 */
	private final UserInfo owner;

	/**
	 * Base image this upload is a new version for.
	 */
	private final ImageDetailsRead image;

	/**
	 * Flags to set for this new image version. Optional field.
	 */
	private ImageVersionWrite versionSettings = null;

	/**
	 * Description of this VM - binary dump of e.g. the *.vmx file (VMware)
	 */
	private final byte[] machineDescription;

	/**
	 * Indicated whether the version information was written to db already.
	 * Disallow setVersionData in that case.
	 */
	private final AtomicBoolean versionWrittenToDb = new AtomicBoolean();

	/**
	 * Set if this is a download from the master server
	 */
	private final TransferInformation masterTransferInfo;

	/**
	 * Optional error message to send to client when rejecting incoming
	 * connection
	 */
	private String errorMessage = null;
	
	/**
	 * Timestamp when this transfer was created
	 */
	private final long initTimestamp = System.currentTimeMillis();

	public IncomingDataTransfer(String uploadId, UserInfo owner, ImageDetailsRead image,
			File destinationFile, long fileSize, List<byte[]> sha1Sums, byte[] machineDescription,
			boolean repairUpload) throws FileNotFoundException {
		super(uploadId, destinationFile, fileSize, sha1Sums, StorageChunkSource.instance);
		this.owner = repairUpload ? null : owner;
		this.image = image;
		this.machineDescription = machineDescription;
		this.masterTransferInfo = null;
		initCommonUpload();
	}

	public IncomingDataTransfer(ImagePublishData publishData, File tmpFile, TransferInformation transferInfo,
			boolean repairUpload) throws FileNotFoundException {
		super(publishData.imageVersionId, tmpFile, publishData.fileSize,
				ThriftUtil.unwrapByteBufferList(transferInfo.blockHashes), StorageChunkSource.instance);
		ImageDetailsRead idr = new ImageDetailsRead();
		idr.setCreateTime(publishData.createTime);
		idr.setDescription(publishData.description);
		idr.setImageBaseId(publishData.imageBaseId);
		idr.setImageName(publishData.imageName);
		idr.setIsTemplate(publishData.isTemplate);
		idr.setLatestVersionId(publishData.imageVersionId);
		idr.setOsId(publishData.osId);
		idr.setOwnerId(publishData.owner.userId);
		idr.setTags(publishData.tags);
		idr.setUpdaterId(publishData.uploader.userId);
		idr.setUpdateTime(publishData.createTime);
		idr.setVirtId(publishData.virtId);
		this.owner = repairUpload ? null : publishData.uploader;
		this.image = idr;
		this.machineDescription = ThriftUtil.unwrapByteBuffer(transferInfo.machineDescription);
		this.masterTransferInfo = transferInfo;
		this.versionSettings = new ImageVersionWrite(false);
		initCommonUpload();
	}

	private void initCommonUpload() {
		SscMode sscMode = RuntimeConfig.get().serverSideCopy;
		if (sscMode == SscMode.OFF) {
			super.enableServerSideCopying(false);
		} else if (sscMode == SscMode.ON) {
			super.enableServerSideCopying(true);
		}
		// Handle repair upload...
		if (!isRepairUpload())
			return;
		if (getTmpFileName().exists() && getTmpFileName().length() > 0) {
			try {
				List<Boolean> statusList = DbImageBlock.getMissingStatusList(getVersionId());
				if (!statusList.isEmpty()) {
					getChunks().resumeFromStatusList(statusList, getTmpFileName().length());
					for (int i = 0; i < 3; ++i) {
						queueUnhashedChunk(false);
					}
				}
			} catch (SQLException e) {
			}
		}
	}

	/**
	 * Called periodically if this is a transfer from the master server, so we
	 * can make sure the transfer is running.
	 */
	public void heartBeat(ExecutorService pool) {
		if (masterTransferInfo == null)
			return;
		if (connectFailCount() > 50)
			return;
		synchronized (this) {
			if (getActiveConnectionCount() >= 1)
				return;
			Downloader downloader = null;
			if (masterTransferInfo.plainPort != 0) {
				try {
					downloader = new Downloader(Configuration.getMasterServerAddress(),
							masterTransferInfo.plainPort, Constants.TRANSFER_TIMEOUT, null,
							masterTransferInfo.token);
				} catch (Exception e1) {
					LOGGER.debug("Plain connect failed", e1);
					downloader = null;
				}
			}
			if (downloader == null && masterTransferInfo.sslPort != 0) {
				try {
					downloader = new Downloader(Configuration.getMasterServerAddress(),
							masterTransferInfo.sslPort, Constants.TRANSFER_TIMEOUT, SSLContext.getDefault(), // TODO: Use the TLSv1.2 one once the master is ready
							masterTransferInfo.token);
				} catch (Exception e2) {
					LOGGER.debug("SSL connect failed", e2);
					downloader = null;
				}
			}
			if (downloader == null) {
				LOGGER.warn("Could not connect to master server for downloading " + image.imageName);
				return;
			}
			addConnection(downloader, pool);
		}
	}

	/**
	 * Set meta data for this image version.
	 * 
	 * @param user
	 * 
	 * @param data
	 */
	public boolean setVersionData(UserInfo user, ImageVersionWrite data) {
		if (isRepairUpload())
			return false;
		synchronized (versionWrittenToDb) {
			if (versionWrittenToDb.get()) {
				return false;
			}
			if (!user.userId.equals(owner.userId)) {
				return false;
			}
			versionSettings = new ImageVersionWrite(data);
			return true;
		}
	}

	/**
	 * Called when the upload finished.
	 */
	@Override
	protected synchronized boolean finishIncomingTransfer() {
		if (getState() != TransferState.FINISHED) {
			LOGGER.warn("finishIncomingTransfer called in bad state " + getState());
			return false;
		}
		potentialFinishTime.set(System.currentTimeMillis());
		// If owner is not set, this was a repair-transfer, which downloads directly to the existing target file.
		// Nothing more to do in that case.
		if (isRepairUpload()) {
			try {
				DbImage.markValid(true, false, DbImage.getLocalImageData(getVersionId()));
			} catch (TNotFoundException e) {
				LOGGER.warn("Apparently, the image " + getVersionId()
						+ " that was just repaired doesn't exist...");
			} catch (SQLException e) {
			}
			return true;
		}
		// It's a fresh upload
		LOGGER.info("Finalizing uploaded image " + image.imageName);
		// Ready to go. First step: Rename temp file to something usable
		String ext = "img";
		try (DiskImage inst = DiskImage.newInstance(getTmpFileName())) {
			ext = inst.getFormat().getExtension();
		} catch (IOException | DiskImageException e1) {
		}
		File destination = new File(getTmpFileName().getParent(), Formatter.vmName(initTimestamp, owner, image.imageName,
				ext)).getAbsoluteFile();
		// Sanity check: destination should be a sub directory of the vmStorePath
		String relPath = FileSystem.getRelativePath(destination, Configuration.getVmStoreBasePath());
		if (relPath == null) {
			LOGGER.error(destination.getAbsolutePath() + " is not a subdir of "
					+ Configuration.getVmStoreBasePath().getAbsolutePath());
			cancel();
			return false;
		}
		if (relPath.length() > 200) {
			LOGGER.error("Generated file name is >200 chars. DB will not like it");
		}

		// Execute rename
		try {
			Path d = Files.move(getTmpFileName().toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
			if (d != null && d.toFile().exists()) {
				destination = d.toFile();
			}
		} catch (IOException e1) {
			LOGGER.warn("Cannot rename", e1);
		}
		if (!destination.exists()) {
			try {
				getTmpFileName().renameTo(destination);
			} catch (Exception e) {
				LOGGER.warn("Cannot rename", e);
			}
		}
		if (!destination.exists()) {
			// Rename failed :-(
			LOGGER.warn("Could not rename '" + getTmpFileName().getAbsolutePath() + "' to '"
							+ destination.getAbsolutePath());
			cancel();
			return false;
		}
		
		if (destination.length() != getFileSize()) {
			LOGGER.warn("Destination file size mismatch. Is: " + destination.length() + ", should be: " + getFileSize());
		}

		// Now insert meta data into DB
		try {
			synchronized (versionWrittenToDb) {
				LOGGER.debug("Owner id " + owner);
				DbImage.createImageVersion(image.imageBaseId, getVersionId(), owner, getFileSize(), relPath,
						versionSettings, getChunks(), machineDescription);
				versionWrittenToDb.set(true);
			}
			DbLog.log(owner, image.imageBaseId, "Successfully uploaded new version " + getVersionId()
					+ " of VM '" + image.imageName + "'");
		} catch (SQLException e) {
			LOGGER.error("Error finishing upload: Inserting version to DB failed", e);
			// Also delete uploaded file, as there is no reference to it
			LOGGER.info("Deleting file " + destination, e);
			FileSystem.deleteAsync(destination);
			cancel();
			return false;
		}
		// Dump CRC32 list
		byte[] dnbd3Crc32List = null;
		try {
			dnbd3Crc32List = getChunks().getDnbd3Crc32List();
		} catch (Exception e) {
			LOGGER.warn("Could not get CRC32 list for upload of " + image.getImageName(), e);
		}
		if (dnbd3Crc32List != null) {
			String crcfile = destination.getAbsolutePath() + ".crc";
			try (FileOutputStream fos = new FileOutputStream(crcfile)) {
				fos.write(dnbd3Crc32List);
			} catch (Exception e) {
				LOGGER.warn("Could not write CRC32 list for DNBD3 at " + crcfile, e);
			}
		}
		return true;
	}

	public String getVersionId() {
		if (masterTransferInfo == null)
			return getId();
		return image.latestVersionId;
	}
	
	public String getBaseId() {
		return image.imageBaseId;
	}

	@Override
	public synchronized void cancel() {
		if (!isRepairUpload() && getTmpFileName().exists()) {
			super.cancel();
			LOGGER.debug("Deleting file " + getTmpFileName(), new RuntimeException());
			FileSystem.deleteAsync(getTmpFileName());
		}
	}

	public boolean isRepairUpload() {
		return owner == null;
	}

	/**
	 * Get user owning this upload. Can be null in special cases.
	 * 
	 * @return instance of UserInfo for the according user.
	 */
	public UserInfo getOwner() {
		return this.owner;
	}

	@Override
	protected void finalize() {
		try {
			super.finalize();
		} catch (Throwable t) {
		}
		try {
			cancel();
		} catch (Throwable t) {
		}
	}

	@Override
	protected boolean hasEnoughFreeSpace() {
		return FileSystem.getAvailableStorageBytes() > MIN_FREE_SPACE_BYTES;
	}

	@Override
	public TransferInformation getTransferInfo() {
		return new TransferInformation(getId(), FileServer.instance().getPlainPort(), FileServer.instance()
				.getSslPort());
	}

	@Override
	public String getRelativePath() {
		return FileSystem.getRelativePath(getTmpFileName(), Configuration.getVmStoreBasePath());
	}

	@Override
	protected void chunkStatusChanged(FileChunk chunk) {
		if (chunk.getFailCount() > 3) {
			cancel();
			errorMessage = "Uploaded file is corrupted - did you modify the VM while uploading?";
			DbLog.log(owner, image.imageBaseId, "Server is cancelling upload of Version " + getVersionId()
					+ " for '" + image.imageName + "': Hash check for block " + chunk.getChunkIndex()
					+ " failed " + chunk.getFailCount()
					+ " times. Maybe the user was still running the VM when starting the upload.");
		}
		if (isRepairUpload()) {
			// Repair uploads write to the database while making progress
			ChunkStatus status = chunk.getStatus();
			if (status == ChunkStatus.MISSING || status == ChunkStatus.COMPLETE) {
				try {
					DbImageBlock.asyncUpdate(getVersionId(), chunk);
				} catch (InterruptedException e) {
				}
			}
		}
	}
	
	// Measure speed for automatic server-side copy
	private final AtomicInteger speedCounter = new AtomicInteger();
	private long speedTimestamp = 0;
	private static final long SSC_ENABLE_THRES = 10l * 1024 * 1024;
	private static final long SSC_DISABLE_THRES = 20l * 1024 * 1024;

	@Override
	protected boolean chunkReceived(FileChunk chunk, byte[] data) {
		SscMode sscMode = RuntimeConfig.get().serverSideCopy;
		if (sscMode == SscMode.AUTO) {
			// Automatic SSC setting
			long diff = 0;
			long bytes;
			synchronized (speedCounter) {
				bytes = speedCounter.addAndGet(chunk.range.getLength());
				if (bytes >= FileChunk.CHUNK_SIZE * 3) {
					diff = System.currentTimeMillis() - speedTimestamp;
					speedTimestamp = System.currentTimeMillis();
				}
			}
			if (diff >= 1000 && diff < 100000000) {
				// Time to evaluate the situation
				long speed = bytes / (diff / 1000);
				if (speed < SSC_ENABLE_THRES) {
					super.enableServerSideCopying(true);
				} else if (speed > SSC_DISABLE_THRES) {
					super.enableServerSideCopying(false);
				}
			}
			
		} else {
			speedTimestamp = 0;
			if (sscMode == SscMode.OFF) {
				super.enableServerSideCopying(false);
			} else if (sscMode == SscMode.ON) {
				super.enableServerSideCopying(true);
			}
		}
		// Hashing
		if (getHashChecker() == null)
			return false;
		try {
			getHashChecker().queue(chunk, data, null, HashChecker.BLOCKING | HashChecker.CALC_CRC32);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return false;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	/**
	 * Alter options of this upload. Returns new effective options.
	 */
	public UploadOptions setOptions(UploadOptions options) {
		if (RuntimeConfig.get().serverSideCopy == SscMode.USER) {
			// User can fiddle around
			if (options != null) {
				if (options.isSetServerSideCopying()) {
					super.enableServerSideCopying(options.serverSideCopying);
				}
			}
		}
		return new UploadOptions(super.isServerSideCopyingEnabled());
	}

}
