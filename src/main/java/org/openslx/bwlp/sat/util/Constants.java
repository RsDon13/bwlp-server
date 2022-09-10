package org.openslx.bwlp.sat.util;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.filetransfer.util.FileChunk;

public class Constants {

	private static final Logger LOGGER = LogManager.getLogger(Constants.class);

	public static final String INCOMPLETE_UPLOAD_SUFFIX = ".upload.partial";
	public static final int MAX_UPLOADS;
	public static final int MAX_UPLOADS_PER_USER;
	public static final int MAX_DOWNLOADS;
	public static final int MAX_CONNECTIONS_PER_TRANSFER;
	public static final int MAX_MASTER_UPLOADS = 2;
	public static final int MAX_MASTER_DOWNLOADS = 3;
	public static final int TRANSFER_TIMEOUT = 15 * 1000; // 15s

	public static final int HASHCHECK_QUEUE_LEN;

	static {
		long maxMem = Runtime.getRuntime().maxMemory();
		if (maxMem == Long.MAX_VALUE) {
			// Apparently the JVM was started without a memory limit (no -Xmx cmdline),
			// so we try a dirty little trick by assuming this is linux and reading it
			// from the /proc file system. If that fails too, assume a default of 512MB
			try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
				for (String line; (line = br.readLine()) != null;) {
					if (line.startsWith("MemTotal:") && line.endsWith("kB")) {
						String string = line.replaceAll("[^0-9]", "");
						try {
							maxMem = (Long.parseLong(string) / 2l) * 1024l;
							LOGGER.debug("Guessing usable JVM memory via /proc/meminfo");
						} catch (Exception e) {
						}
						break;
					}
				}
			} catch (IOException e) {
			}
			if (maxMem == Long.MAX_VALUE) {
				maxMem = 512l * 1024l * 1024l;
			}
		}
		maxMem /= 1024l * 1024l;
		// Now maxMem is the amount of memory in MiB
		LOGGER.debug("Maximum JVM memory: " + maxMem + "MiB");

		int cpuCount = Runtime.getRuntime().availableProcessors();
		int hashQueueLen = (int) (maxMem / 100);
		if (hashQueueLen < 1) {
			hashQueueLen = 1;
		} else if (hashQueueLen > 6) {
			hashQueueLen = 6;
		}
		int maxPerTransfer = (int) Math.max(1, (maxMem - 400) / (FileChunk.CHUNK_SIZE_MIB * 8));
		if (maxPerTransfer > 4) {
			maxPerTransfer = 4;
		}
		if (maxPerTransfer > cpuCount) {
			maxPerTransfer = cpuCount;
		}
		int maxUploads = (int) Math.max(1, (maxMem - 64) / (FileChunk.CHUNK_SIZE_MIB * (hashQueueLen + 1)));
		if (maxUploads > cpuCount * 4) {
			maxUploads = cpuCount * 4;
		}

		MAX_CONNECTIONS_PER_TRANSFER = maxPerTransfer;
		MAX_UPLOADS = maxUploads;
		MAX_DOWNLOADS = MAX_UPLOADS * 2;
		MAX_UPLOADS_PER_USER = Math.min(MAX_UPLOADS / 2, 4);
		HASHCHECK_QUEUE_LEN = hashQueueLen;
	}
}
