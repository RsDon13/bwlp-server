package org.openslx.bwlp.sat.maintenance;

import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

import org.joda.time.DateTime;
import org.openslx.bwlp.sat.database.mappers.DbLecture;
import org.openslx.util.QuickTimer;
import org.openslx.util.QuickTimer.Task;

/**
 * Delete old image versions (images that reached their expire time).
 */
public class DeleteOldLectures implements Runnable {

	private static final DeleteOldLectures instance = new DeleteOldLectures();

	private static long blockedUntil = 0;

	/**
	 * Initialize the delete task. This schedules a timer that runs
	 * every 7 minutes. If the hour of day reaches 4, it will fire
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
				if (now.getHourOfDay() != 4 || now.getMinuteOfHour() > 15)
					return;
				start();
			}
		}, TimeUnit.MINUTES.toMillis(6), TimeUnit.MINUTES.toMillis(7));
	}

	public synchronized static void start() {
		if (blockedUntil > System.currentTimeMillis())
			return;
		if (Maintenance.trySubmit(instance)) {
			blockedUntil = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(12);
		}
	}

	private DeleteOldLectures() {
	}

	@Override
	public void run() {
		try {
			DbLecture.deleteOld(365);
		} catch (SQLException e) {
		}
	}

}
