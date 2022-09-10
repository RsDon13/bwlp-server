package org.openslx.bwlp.sat.maintenance;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joda.time.DateTime;
import org.openslx.bwlp.sat.database.mappers.DbImage;
import org.openslx.bwlp.sat.database.mappers.DbImage.DeleteState;
import org.openslx.bwlp.sat.database.mappers.DbLog;
import org.openslx.bwlp.sat.database.models.LocalImageVersion;
import org.openslx.bwlp.sat.util.FileSystem;
import org.openslx.bwlp.sat.util.Formatter;
import org.openslx.util.QuickTimer;
import org.openslx.util.QuickTimer.Task;
import org.openslx.util.Util;

/**
 * Delete old image versions (images that reached their expire time).
 */
public class DeleteOldImages implements Runnable {

	private static final Logger LOGGER = LogManager.getLogger(DeleteOldImages.class);

	private static final DeleteOldImages instance = new DeleteOldImages();

	private static long blockedUntil = 0;

	/**
	 * Initialize the delete task. This schedules a timer that runs
	 * every 5 minutes. If the hour of day reaches 3, it will fire
	 * the task, and block it from running for the next 12 hours.
	 */
	public synchronized static void init() {
		if (blockedUntil != 0)
			return;
		blockedUntil = 1;
		QuickTimer.scheduleAtFixedRate(new Task() {
			@Override
			public void fire() {
				if (blockedUntil > System.currentTimeMillis())
					return;
				DateTime now = DateTime.now();
				if (now.getHourOfDay() != 3 || now.getMinuteOfHour() > 15)
					return;
				start();
			}
		}, TimeUnit.MINUTES.toMillis(5), TimeUnit.MINUTES.toMillis(5));
	}

	public synchronized static void start() {
		if (blockedUntil > System.currentTimeMillis())
			return;
		if (Maintenance.trySubmit(instance)) {
			blockedUntil = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(12);
		}
	}

	private DeleteOldImages() {
	}

	@Override
	public void run() {
		// Get all images currently marked as "Should delete" and reset them to "keep"
		Set<String> resetList;
		try {
			resetList = DbImage.resetDeleteState();
		} catch (SQLException e1) {
			resetList = new HashSet<>();
		}
		if (!FileSystem.isStorageMounted()) {
			LOGGER.warn("Will not execute deletion of old images; store seems to be unmounted!");
			return;
		}
		LOGGER.info("Looking for old image versions to delete");
		Set<LocalImageVersion> versions = new HashSet<>();
		// First get a list of all image versions which reached their expire date,
		// no matter if valid or invalid
		try {
			List<LocalImageVersion> list = DbImage.getExpiringLocalImageVersions(0);
			versions.addAll(list);
		} catch (SQLException e) {
			LOGGER.error("Will not be able to clean up old image versions");
		}
		try {
			List<LocalImageVersion> list = DbImage.getVersionsWithMissingData();
			versions.addAll(list);
		} catch (SQLException e) {
			LOGGER.error("Will not be able to clean up invalid image versions");
		}
		// Mark all as invalid. This will also trigger mails if they have been valid before
		try {
			DbImage.markValid(false, false, versions.toArray(new LocalImageVersion[versions.size()]));
		} catch (SQLException e) {
			LOGGER.error("Could not mark images to be deleted as invalid. Cleanup of old images failed.");
			return;
		}
		int hardDeleteCount = 0;
		final long hardDelete = Util.unixTime() - 86400;
		for (LocalImageVersion version : versions) {
			if (version.expireTime < hardDelete) {
				// Delete them permanently only if they expired (at least) one day ago
				hardDeleteCount++;
				try {
					DbImage.setDeletion(DeleteState.SHOULD_DELETE, version.imageVersionId);
				} catch (SQLException e) {
				}
			}
			// Remove all versions from our reset list that were just disabled again, so we keep those
			// that have potentially been falsely disabled before
			resetList.remove(version.imageVersionId);
		}
		// Delete base images with no image versions (including invalid ones)
		int baseDeleteCount = 0;
		try {
			baseDeleteCount = DbImage.deleteOrphanedBases();
		} catch (SQLException e) {
			// Logging done in method
		}
		LOGGER.info("Deletion done. Soft: " + (versions.size() - hardDeleteCount) + ", hard: "
				+ hardDeleteCount + ", base: " + baseDeleteCount);
		// Aftermath: We might have a list of image versions that have been un-marked from deletion,
		// and weren't re-marked in this run. This means there might have been clock skew or other problems.
		// So let's check those images' files, and if they're ok, we also set the 'isvalid' flag again
	}

	public static StringBuilder hardDeleteImages() {
		StringBuilder sb = new StringBuilder();
		List<LocalImageVersion> deletables;
		try {
			deletables = DbImage.getLocalWithState(DeleteState.WANT_DELETE);
		} catch (SQLException e2) {
			return null;
		}
		for (LocalImageVersion version : deletables) {
			FileSystem.deleteImageRelatedFiles(version);
			try {
				DbImage.deleteVersionPermanently(version);
			} catch (SQLException e) {
				writeln(sb, version.imageVersionId, ": Cannot delete image: ", e.getMessage());
			}
			writeln(sb, version.imageVersionId, ": OK");
			DbLog.log((String)null, version.imageBaseId,
					"Version " + version.imageVersionId + " (" + Formatter.date(version.createTime)
							+ ") deleted from database and storage.");
		}
		writeln(sb, "Done");
		return sb;
	}

	private static void writeln(StringBuilder sb, String... parts) {
		for (String s : parts) {
			if (s == null) {
				sb.append("(null)");
			} else {
				sb.append(s);
			}
		}
		sb.append('\n');
	}

	public static void hardDeleteImagesAsync() {
		Maintenance.trySubmit(new Runnable() {
			@Override
			public void run() {
				hardDeleteImages();
			}
		});
	}

}
