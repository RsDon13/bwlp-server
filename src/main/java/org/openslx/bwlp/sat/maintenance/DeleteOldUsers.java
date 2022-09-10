package org.openslx.bwlp.sat.maintenance;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joda.time.DateTime;
import org.openslx.bwlp.sat.database.mappers.DbLog;
import org.openslx.bwlp.sat.database.mappers.DbUser;
import org.openslx.bwlp.sat.util.Formatter;
import org.openslx.bwlp.thrift.iface.UserInfo;
import org.openslx.util.QuickTimer;
import org.openslx.util.QuickTimer.Task;

/**
 * Delete old image versions (images that reached their expire time).
 */
public class DeleteOldUsers implements Runnable {

	private static final Logger LOGGER = LogManager.getLogger(DeleteOldUsers.class);

	private static final DeleteOldUsers instance = new DeleteOldUsers();

	private static long blockedUntil = 0;

	/**
	 * Initialize the delete task. This schedules a timer that runs
	 * every 6 minutes. If the hour of day reaches 1, it will fire
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
				if (now.getHourOfDay() != 1 || now.getMinuteOfHour() > 15)
					return;
				start();
			}
		}, TimeUnit.MINUTES.toMillis(5), TimeUnit.MINUTES.toMillis(6));
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
		List<UserInfo> inactiveUsers;
		try {
			inactiveUsers = DbUser.getInactive();
		} catch (SQLException e) {
			LOGGER.warn("Cannot get list of old users for deletion");
			return;
		}
		for (UserInfo user : inactiveUsers) {
			try {
				if (DbUser.deleteUser(user)) {
					DbLog.log((String)null, null, "Deleted inactive user " + Formatter.userFullName(user));
				}
			} catch (SQLException e) {
				// Already logged
			}
		}
	}

}
