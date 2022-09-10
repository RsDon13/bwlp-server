package org.openslx.bwlp.sat.maintenance;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.bwlp.sat.database.mappers.DbImage;
import org.openslx.bwlp.sat.database.mappers.DbImageBlock;
import org.openslx.bwlp.sat.database.models.ImageVersionMeta;
import org.openslx.bwlp.sat.database.models.LocalImageVersion;
import org.openslx.bwlp.sat.util.FileSystem;
import org.openslx.bwlp.thrift.iface.TNotFoundException;
import org.openslx.filetransfer.util.ChunkStatus;
import org.openslx.filetransfer.util.FileChunk;
import org.openslx.filetransfer.util.HashChecker;
import org.openslx.filetransfer.util.HashChecker.HashCheckCallback;
import org.openslx.filetransfer.util.HashChecker.HashResult;
import org.openslx.filetransfer.util.StandaloneFileChunk;
import org.openslx.util.ThriftUtil;
import org.openslx.util.TimeoutHashMap;
import org.openslx.util.Util;

public class ImageValidCheck implements Runnable {

	public static enum CheckResult {
		
		QUEUED(0),
		NO_SUCH_JOB(0),
		WAITING_FOR_STORE(1),
		WORKING(2),
		DONE(100),
		VERSION_EXPIRED(100),
		DATABASE_PATH_MISSING(100),
		DATABASE_PATH_INVALID(100),
		FILE_NOT_FOUND(100),
		FILE_ACCESS_ERROR(100),
		FILE_SIZE_MISMATCH(100),
		FILE_CORRUPT(100),
		UNKNOWN_VERSIONID(100),
		INTERNAL_ERROR(100);
		public final int stage;
		private CheckResult(int stage) {
			this.stage = stage;
		}
	}
	
	public static enum SubmitResult {
		QUEUED,
		NULL_POINTER_EXCEPTION,
		ALREADY_IN_PROGRESS,
		TOO_MANY_QUEUED_JOBS,
		REJECTED_BY_SCHEDULER,
	}

	private static final Logger LOGGER = LogManager.getLogger(ImageValidCheck.class);
	
	private static final int MAX_CONCURRENT_CHECKS = 1;

	private static Queue<ImageValidCheck> queue = new LinkedList<>();
	private static Map<String, ImageValidCheck> inProgress = new HashMap<>();
	private static TimeoutHashMap<String, ImageValidCheck> done = new TimeoutHashMap<>(
			TimeUnit.MINUTES.toMillis(60));

	private final String versionId;
	private final boolean integrity;
	private final boolean updateState;

	/**
	 * Status of this check job. Must never be null.
	 */
	private CheckResult result = CheckResult.QUEUED;


	// Hash checking

	private static final HashChecker hashChecker;

	static {
		long maxMem = Runtime.getRuntime().maxMemory() / (1024 * 1024);
		int hashQueueLen;
		if (maxMem < 1200) {
			hashQueueLen = 1;
		} else {
			hashQueueLen = 2;
		}
		HashChecker hc;
		try {
			hc = new HashChecker("SHA-1", hashQueueLen);
		} catch (NoSuchAlgorithmException e) {
			hc = null;
		}
		hashChecker = hc;
	}

	// End hash checking

	public static SubmitResult check(String versionId, boolean integrity, boolean updateState) {
		if (versionId == null)
			return SubmitResult.NULL_POINTER_EXCEPTION;
		synchronized (inProgress) {
			synchronized (done) {
				if (done.containsKey(versionId)) {
					done.remove(versionId);
				}
			}
			if (inProgress.containsKey(versionId))
				return SubmitResult.ALREADY_IN_PROGRESS;
			if (inProgress.size() >= MAX_CONCURRENT_CHECKS) {
				if (queue.size() > 1000) {
					return SubmitResult.TOO_MANY_QUEUED_JOBS;
				}
				queue.add(new ImageValidCheck(versionId, integrity, updateState));
				return SubmitResult.QUEUED;
			}
			ImageValidCheck check = new ImageValidCheck(versionId, integrity, updateState);
			if (Maintenance.trySubmit(check)) {
				inProgress.put(versionId, check);
				return SubmitResult.QUEUED;
			}
		}
		return SubmitResult.REJECTED_BY_SCHEDULER;
	}

	public static void checkForWork() {
		synchronized (inProgress) {
			while (inProgress.size() < MAX_CONCURRENT_CHECKS && !queue.isEmpty()) {
				ImageValidCheck check = queue.poll();
				if (check == null)
					break;
				if (inProgress.containsKey(check.versionId))
					continue; // Already checking this version, try next in queue
				if (Maintenance.trySubmit(check)) {
					inProgress.put(check.versionId, check);
				} else {
					if (!queue.offer(check)) {
						LOGGER.warn("Dropped queued check for image version " + check.versionId);
					}
					// Scheduler didn't accept job - don't try remaining queue
					break;
				}
			}
		}
	}

	/**
	 * Get current status of check for given versionId.
	 * Never returns null.
	 * 
	 * @param versionId VERSIONSID DES IMAGES
	 * @return state/result
	 */
	public static CheckResult getStatus(String versionId) {
		ImageValidCheck i;
		synchronized (inProgress) {
			i = inProgress.get(versionId);
		}
		if (i != null)
			return i.result;
		synchronized (done) {
			i = done.get(versionId);
		}
		if (i != null)
			return i.result;
		return CheckResult.NO_SUCH_JOB;
	}

	/**
	 * Get status/result of all known check jobs.
	 * 
	 * @return MAP
	 */
	public static Map<String, CheckResult> getAll() {
		Map<String, CheckResult> res = new HashMap<>();
		synchronized (inProgress) {
			for (Entry<String, ImageValidCheck> i : inProgress.entrySet()) {
				res.put(i.getKey(), i.getValue().result);
			}
		}
		synchronized (done) {
			for (Entry<String, ImageValidCheck> i : done.getImmutableSnapshot().entrySet()) {
				res.put(i.getKey(), i.getValue().result);
			}
		}
		return res;
	}

	//
	// Instance
	//

	private ImageValidCheck(String versionId, boolean integrity, boolean updateState) {
		this.versionId = versionId;
		this.integrity = integrity;
		this.updateState = updateState;
	}

	private void setState(CheckResult cr) {
		if (cr.stage > result.stage) {
			result = cr;
		} else {
			LOGGER.debug("Ingoring state update from " + result.name() + " to " + cr.name());
		}
	}

	@Override
	public void run() {
		try {
			setState(CheckResult.WAITING_FOR_STORE);
			if (!FileSystem.waitForStorage()) {
				LOGGER.warn("Will not check " + versionId + ": Storage not online");
				setState(CheckResult.INTERNAL_ERROR);
				return;
			}
			setState(CheckResult.WORKING);
			LocalImageVersion imageVersion;
			try {
				imageVersion = DbImage.getLocalImageData(versionId);
			} catch (TNotFoundException e) {
				LOGGER.warn("Cannot check validity of image version - not found: " + versionId);
				setState(CheckResult.UNKNOWN_VERSIONID);
				return;
			} catch (Exception e) {
				LOGGER.warn("Cannot get local image data", e);
				setState(CheckResult.INTERNAL_ERROR);
				return;
			}
			// Found image in DB
			// Simple checks first
			boolean valid = checkValid(imageVersion);
			if (valid && integrity) {
				// Check block hashes
				try {
					valid = checkBlockHashes(imageVersion);
				} catch (IOException e) {
					LOGGER.warn("IO error for " + versionId, e);
					setState(CheckResult.FILE_ACCESS_ERROR);
					valid = false;
				} catch (Exception e) {
					LOGGER.warn("Cannot check block hashes of " + versionId, e);
					setState(CheckResult.INTERNAL_ERROR);
				}
			}
			if (imageVersion.isValid == valid) {
				// nothing changed
				if (valid) {
					setState(CheckResult.DONE);
				}
				return;
			}
			// Update
			try {
				if (updateState) {
					DbImage.markValid(valid, false, imageVersion);
				}
				if (valid) {
					setState(CheckResult.DONE);
				}
			} catch (SQLException e) {
				setState(CheckResult.INTERNAL_ERROR);
			}
		} finally {
			if (result == CheckResult.WORKING) {
				setState(CheckResult.INTERNAL_ERROR);
			}
			ImageValidCheck ivc;
			synchronized (inProgress) {
				synchronized (done) {
					ivc = inProgress.remove(this.versionId);
					done.put(this.versionId, ivc);
				}
			}
			checkForWork();
		}
	}

	/**
	 * Do a complete hash check of the given image file.
	 */
	private boolean checkBlockHashes(final LocalImageVersion imageVersion) throws IOException,
			InterruptedException {
		ImageVersionMeta versionDetails;
		try {
			versionDetails = DbImage.getVersionDetails(versionId);
		} catch (TNotFoundException e) {
			LOGGER.warn("Cannot check hash of image version - not found: " + versionId);
			setState(CheckResult.UNKNOWN_VERSIONID);
			return false;
		} catch (SQLException e) {
			setState(CheckResult.INTERNAL_ERROR);
			return false;
		}
		if (versionDetails.sha1sums == null || versionDetails.sha1sums.isEmpty()) {
			LOGGER.info("Image does not have block hashes -- assuming ok");
			return true;
		}
		int numChecked = 0;
		final Semaphore sem = new Semaphore(0);
		final AtomicBoolean fileOk = new AtomicBoolean(true);
		File path = FileSystem.composeAbsoluteImagePath(imageVersion);
		try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
			long startOffset = 0;
			for (ByteBuffer hash : versionDetails.sha1sums) {
				if (hash == null) {
					startOffset += FileChunk.CHUNK_SIZE;
					continue;
				}
				long endOffset = startOffset + FileChunk.CHUNK_SIZE;
				if (endOffset > imageVersion.fileSize) {
					endOffset = imageVersion.fileSize;
				}
				StandaloneFileChunk chunk = new StandaloneFileChunk(startOffset, endOffset,
						ThriftUtil.unwrapByteBuffer(hash));
				byte[] buffer = new byte[(int) (endOffset - startOffset)];
				raf.seek(startOffset);
				raf.readFully(buffer);
				hashChecker.queue(chunk, buffer, new HashCheckCallback() {
					@Override
					public void hashCheckDone(HashResult result, byte[] data, FileChunk chunk) {
						try {
							if (result == HashResult.FAILURE) {
								// Hashing failed, cannot tell whether OK or not :(
							} else {
								if (result == HashResult.INVALID) {
									fileOk.set(false);
									((StandaloneFileChunk) chunk).overrideStatus(ChunkStatus.MISSING);
								} else {
									// >:(
									((StandaloneFileChunk) chunk).overrideStatus(ChunkStatus.COMPLETE);
								}
								try {
									// We don't know what the state was in DB before, so just fire updates
									DbImageBlock.asyncUpdate(imageVersion.imageVersionId, chunk);
								} catch (InterruptedException e) {
									Thread.currentThread().interrupt();
								}
							}
						} finally {
							sem.release();
						}
					}
				}, HashChecker.BLOCKING | HashChecker.CALC_HASH);
				numChecked += 1;
				startOffset += FileChunk.CHUNK_SIZE;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw e;
		}
		// Wait until the last callback fired
		sem.acquire(numChecked);
		if (fileOk.get()) {
			return true;
		}
		setState(CheckResult.FILE_CORRUPT);
		return false;
	}

	/**
	 * "Inexpensive" validity checks. File exists, readable, size ok, etc.
	 */
	private boolean checkValid(LocalImageVersion imageVersion) {
		if (imageVersion == null)
			return false;
		if (imageVersion.expireTime < Util.unixTime()) {
			LOGGER.info(versionId + ": expired");
			setState(CheckResult.VERSION_EXPIRED);
			return false;
		}
		if (imageVersion.filePath == null || imageVersion.filePath.isEmpty()) {
			LOGGER.info(versionId + ": DB does not contain a path");
			setState(CheckResult.DATABASE_PATH_MISSING);
			return false;
		}
		File path = FileSystem.composeAbsoluteImagePath(imageVersion);
		if (path == null) {
			LOGGER.info(versionId + ": path from DB is not valid");
			setState(CheckResult.DATABASE_PATH_INVALID);
			return false;
		}
		if (!path.exists()) {
			LOGGER.info(versionId + ": File does not exist (" + path.getAbsolutePath() + ")");
			setState(CheckResult.FILE_NOT_FOUND);
			return false;
		}
		if (!path.canRead()) {
			LOGGER.info(versionId + ": File exists but not readable (" + path.getAbsolutePath() + ")");
			setState(CheckResult.FILE_ACCESS_ERROR);
			return false;
		}
		if (path.length() != imageVersion.fileSize) {
			LOGGER.info(versionId + ": File exists but has wrong size (expected: " + imageVersion.fileSize
					+ ", found: " + path.length() + ")");
			setState(CheckResult.FILE_SIZE_MISMATCH);
			return false;
		}
		return true;
	}

}
