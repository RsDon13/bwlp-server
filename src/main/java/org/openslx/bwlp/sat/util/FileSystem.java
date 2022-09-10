package org.openslx.bwlp.sat.util;

import java.io.File;

import org.apache.commons.io.FilenameUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.bwlp.sat.database.models.LocalImageVersion;
import org.openslx.util.QuickTimer;
import org.openslx.util.QuickTimer.Task;

public class FileSystem {

	private static final Logger LOGGER = LogManager.getLogger(FileSystem.class);

	public static String getRelativePath(File absolutePath, File parentDir) {
		String file;
		String dir;
		try {
			file = absolutePath.getCanonicalPath();
			dir = parentDir.getCanonicalPath() + File.separator;
		} catch (Exception e) {
			LOGGER.error("Could not get relative path for " + absolutePath.toString(), e);
			return null;
		}
		if (!file.startsWith(dir))
			return null;
		return file.substring(dir.length());
	}

	/**
	 * Delete given file on the {@link QuickTimer} thread, preventing the
	 * calling thread from freezing/hanging on I/O problems.
	 * 
	 * @param sourceCandidates the file to delete
	 */
	public static void deleteAsync(final File... files) {
		if (files == null || files.length == 0)
			return;
		QuickTimer.scheduleOnce(new Task() {
			@Override
			public void fire() {
				for (File file : files) {
					if (file == null)
						continue;
					if (!file.exists()) {
						continue;
					}
					if (!file.delete()) {
						LOGGER.warn("Could not delete file " + file.getAbsolutePath());
					}
				}
			}
		});
	}

	/**
	 * Checks if the VM store is mounted.
	 * 
	 * @return true if mounted, false otherwise.
	 */
	public static boolean isStorageMounted() {
		File flagFile = new File(Configuration.getVmStoreBasePath(), ".notmounted");
		if (flagFile.exists())
			return false;
		File prodPath = Configuration.getVmStoreProdPath();
		if (prodPath.isDirectory())
			return true;
		return prodPath.mkdir();
	}

	private static Object storageMutex = new Object();

	public static boolean waitForStorage() {
		if (isStorageMounted())
			return true;
		synchronized (storageMutex) {
			if (isStorageMounted())
				return true;
			LOGGER.warn("VM storage gone, waiting for it to reappear...");
			long lastComplain = System.currentTimeMillis();
			do {
				try {
					Thread.sleep(10000);
				} catch (InterruptedException e) {
					LOGGER.warn("Interrupted while waiting", e);
					return false;
				}
				if (System.currentTimeMillis() - lastComplain > 600000) {
					lastComplain = System.currentTimeMillis();
					LOGGER.warn("Still waiting for storage...");
				}
			} while (!isStorageMounted());
		}
		LOGGER.info("VM storage back online");
		return true;
	}

	/**
	 * Delete the given image file (and all related files) from the storage.
	 * 
	 * @param image The image to be deleted
	 */
	public static void deleteImageRelatedFiles(LocalImageVersion image) {
		File imageFile = composeAbsoluteImagePath(image);
		if (imageFile == null)
			return;
		File metaFile = new File(imageFile.getPath() + ".meta");
		File crcFile = new File(imageFile.getPath() + ".crc");
		File mapFile = new File(imageFile.getPath() + ".map");
		if (!waitForStorage())
			return;
		deleteAsync(imageFile, metaFile, crcFile, mapFile);
	}

	/**
	 * Given a local image version, return a {@link File} object that holds the
	 * full path to the image file in the file system.
	 * 
	 * @param localImageData
	 * @return {@link File} representing the physical image file, or
	 *         <code>null</code> if the path would not be valid
	 */
	public static File composeAbsoluteImagePath(LocalImageVersion localImageData) {
		if (localImageData == null)
			return null;
		File path = composeAbsolutePath(localImageData.filePath);
		if (path == null) {
			LOGGER.warn("ImageVersionId is " + localImageData.imageVersionId);
		}
		return path;
	}

	/**
	 * Given a local relative path for an image, return a {@link File} object
	 * that holds the full path to the image file in the file system.
	 * 
	 * @param relativePath
	 * @return {@link File} representing the physical image file, or
	 *         <code>null</code> if the path would not be valid
	 */
	public static File composeAbsolutePath(String relativePath) {
		if (relativePath != null) {
			relativePath = FilenameUtils.normalize(relativePath);
		}
		if (relativePath == null || relativePath.startsWith("/")) {
			LOGGER.warn("Invalid path for local image: " + relativePath);
			return null;
		}
		return new File(Configuration.getVmStoreBasePath(), relativePath);
	}

	private static long lastStorageFailLog = 0;

	/**
	 * Query how much space is left in the vmstore directory.
	 * 
	 * @return free space in vmstore directory, in bytes, or -1 on error
	 */
	public static long getAvailableStorageBytes() {
		if (!isStorageMounted())
			return -1;
		try {
			return Configuration.getVmStoreProdPath().getUsableSpace();
		} catch (Exception e) {
			long now = System.currentTimeMillis();
			if (now - lastStorageFailLog > 60000) {
				lastStorageFailLog = now;
				LOGGER.warn("Could not determine free space of vmstore", e);
			}
			return -1;
		}
	}

}
