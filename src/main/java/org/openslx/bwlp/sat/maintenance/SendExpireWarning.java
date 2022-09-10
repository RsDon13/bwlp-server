package org.openslx.bwlp.sat.maintenance;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joda.time.DateTime;
import org.openslx.bwlp.sat.database.mappers.DbImage;
import org.openslx.bwlp.sat.database.mappers.DbLecture;
import org.openslx.bwlp.sat.database.models.LocalImageVersion;
import org.openslx.bwlp.sat.mail.MailGenerator;
import org.openslx.bwlp.sat.util.FileSystem;
import org.openslx.bwlp.thrift.iface.LectureSummary;
import org.openslx.util.QuickTimer;
import org.openslx.util.QuickTimer.Task;
import org.openslx.util.Util;

public class SendExpireWarning implements Runnable {

	private static final Logger LOGGER = LogManager.getLogger(SendExpireWarning.class);

	private static final SendExpireWarning instance = new SendExpireWarning();

	private static long blockedUntil = 0;

	/**
	 * Initialize the task. This schedules a timer that runs
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
		}, TimeUnit.MINUTES.toMillis(4), TimeUnit.MINUTES.toMillis(5));
	}

	public synchronized static void start() {
		if (blockedUntil > System.currentTimeMillis())
			return;
		if (Maintenance.trySubmit(instance)) {
			blockedUntil = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(12);
		}
	}

	@Override
	public void run() {
		checkImages();
		checkLectures();
	}

	private void checkLectures() {
		List<LectureSummary> lectures;
		try {
			lectures = DbLecture.getExpiringLectures(15);
		} catch (SQLException e) {
			LOGGER.warn("Could not get list of expiring lectures; skipping warning mails");
			return;
		}
		LOGGER.info("Scanning expiring lectures to send mails to users");
		final long now = Util.unixTime();
		for (LectureSummary lecture : lectures) {
			final int days = (int) ((lecture.endTime - now) / 86400);
			if ((lecture.isEnabled && (days == 14 || days == 1)) || (days == 7)) {
				LOGGER.debug(lecture.lectureName + " expires in " + days);
				MailGenerator.sendLectureExpiringReminder(lecture, days);
			}
		}
	}

	private void checkImages() {
		if (!FileSystem.isStorageMounted()) {
			LOGGER.warn("Skipping sending warning mails about expiring images - storage seems unmounted");
			return;
		}
		// Get all images that expire in 15 days or less
		List<LocalImageVersion> versions;
		try {
			versions = DbImage.getExpiringLocalImageVersions(15);
		} catch (SQLException e) {
			LOGGER.warn("Could not determine expiring versions; skipping warning mails");
			return;
		}
		LOGGER.info("Scanning for expiring images to send mails to users");
		// Send reminder on certain days
		final long now = Util.unixTime();
		for (LocalImageVersion version : versions) {
			final int days = (int) ((version.expireTime - now) / 86400);
			boolean mailNormal = (version.isValid && (days == 14 || days == 7 || days == 1))
					|| (!version.isValid && days == 3);
			boolean mailForced = version.isValid && days == 1;
			if (mailNormal || mailForced) {
				LOGGER.debug(version.imageVersionId + " expires in " + days);
				MailGenerator.sendImageDeletionReminder(version, days, mailForced);
			}
		}
	}
}
