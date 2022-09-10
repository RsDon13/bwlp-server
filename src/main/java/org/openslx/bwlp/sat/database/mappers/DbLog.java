package org.openslx.bwlp.sat.database.mappers;

import java.sql.SQLException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.bwlp.sat.database.Database;
import org.openslx.bwlp.sat.database.MysqlConnection;
import org.openslx.bwlp.sat.database.MysqlStatement;
import org.openslx.bwlp.thrift.iface.UserInfo;
import org.openslx.util.QuickTimer;
import org.openslx.util.QuickTimer.Task;
import org.openslx.util.Util;

public class DbLog {

	private static final Logger LOGGER = LogManager.getLogger(DbLog.class);

	/**
	 * Add entry to logging table.
	 * 
	 * @param userId user causing the action (can be null)
	 * @param targetId object being acted upon (userid, lectureid, imageid, or
	 *            null)
	 * @param description Human readable description of the action being
	 *            performed
	 */
	public static void log(final String userId, final String targetId, final String description) {
		final long timeStamp = Util.unixTime();
		QuickTimer.scheduleOnce(new Task() {
			@Override
			public void fire() {
				try (MysqlConnection connection = Database.getConnection()) {
					MysqlStatement stmt = connection.prepareStatement("INSERT INTO actionlog"
							+ " (dateline, userid, targetid, description) VALUES"
							+ " (:dateline, :userid, :targetid, :description)");
					stmt.setLong("dateline", timeStamp);
					stmt.setString("userid", userId);
					stmt.setString("targetid", targetId);
					stmt.setString("description", description == null ? "" : description);
					stmt.executeUpdate();
					connection.commit();
				} catch (SQLException e) {
					LOGGER.error("Query failed in DbLog.log()", e);
				}
			}
		});
	}

	public static void log(UserInfo user, String targetId, String description) {
		log(user == null ? null : user.userId, targetId, description);
	}

}
