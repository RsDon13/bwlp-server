package org.openslx.bwlp.sat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.bwlp.sat.util.Configuration;
import org.openslx.bwlp.sat.util.FileSystem;
import org.openslx.util.QuickTimer.Task;

/**
 * As it might have catastrophic results if two instances of this server operate
 * on the same file storage, we write a randomly generated id to a file on the
 * storage, and check it periodically. If it changed, we bail out in complete
 * panic.
 */
public class StorageUseCheck extends Task {

	private static final Logger LOGGER = LogManager.getLogger(StorageUseCheck.class);

	private final String uuid = UUID.randomUUID().toString();

	private final File canary = new File(Configuration.getVmStoreProdPath(), "dozmod.lock");

	private boolean created = false;

	public StorageUseCheck() {
		if (FileSystem.waitForStorage()) {
			createCanary();
		}
	}

	private void createCanary() {
		if (!FileSystem.isStorageMounted()) {
			LOGGER.warn("Cannot check storage lock, storage not mounted");
			return;
		}
		if (!created || !canary.exists()) {
			try {
				FileUtils.write(canary, uuid, StandardCharsets.UTF_8);
			} catch (IOException e) {
				LOGGER.fatal("Cannot write lock file to VMStore", e);
				System.exit(1);
			}
			created = true;
		} else {
			String canaryContents;
			try {
				canaryContents = FileUtils.readFileToString(canary, StandardCharsets.UTF_8);
			} catch (IOException e) {
				LOGGER.warn("Lock file cannot be accessed. Cannot ensure exclusive use of VMStore", e);
				return;
			}
			if (!canaryContents.equals(uuid)) {
				LOGGER.fatal("Lock file content changed. Another server instance is using the VMStore."
						+ " Will exit immediately to prevent any damages.");
				try {
					FileUtils.write(canary, uuid, StandardCharsets.UTF_8);
				} catch (IOException e) {
				}
				System.exit(1);
			}
		}
	}

	@Override
	public void fire() {
		createCanary();
	}

}
