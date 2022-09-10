package org.openslx.bwlp.sat.database.mappers;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.bwlp.sat.database.Database;
import org.openslx.bwlp.sat.database.MysqlConnection;
import org.openslx.bwlp.sat.database.MysqlStatement;
import org.openslx.bwlp.sat.mail.Mail;

public class DbMailQueue {

	private static final Logger LOGGER = LogManager.getLogger(DbMailQueue.class);

	public static void queue(Mail mail) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("INSERT IGNORE INTO mailqueue"
					+ " (mailid, userid, message, failcount, dateline) VALUES"
					+ " (:mailid, :userid, :message, 0, UNIX_TIMESTAMP())");
			stmt.setString("mailid", mail.id);
			stmt.setString("userid", mail.userId);
			stmt.setString("message", mail.message);
			stmt.executeUpdate();
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbMailQueue.queue()", e);
			throw e;
		}
	}

	public static List<Mail> getQueued(int batchSize) throws SQLException {
		if (batchSize <= 0)
			throw new IllegalArgumentException("batchSize must be > 0");
		try (MysqlConnection connection = Database.getConnection()) {
			// Delete old mails that got stuck in the queue, optimize table
			MysqlStatement delStmt = connection.prepareStatement("DELETE FROM mailqueue"
					+ " WHERE UNIX_TIMESTAMP() - dateline > 86400 * 2");
			delStmt.executeUpdate();
			if (Math.random() < .01) {
				MysqlStatement optStmt = connection.prepareStatement("OPTIMIZE TABLE mailqueue");
				optStmt.execute();
			}
			MysqlStatement stmt = connection.prepareStatement("SELECT"
					+ " mailid, userid, message FROM mailqueue"
					+ " WHERE failcount < 8 ORDER BY dateline ASC LIMIT " + batchSize);
			ResultSet rs = stmt.executeQuery();
			List<Mail> list = new ArrayList<>();
			while (rs.next()) {
				list.add(new Mail(rs.getString("mailid"), rs.getString("userid"), rs.getString("message")));
			}
			connection.commit();
			return list;
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbMailQueue.getQueued()", e);
			throw e;
		}
	}

	public static void markFailed(List<Mail> mails) throws SQLException {
		if (mails.isEmpty())
			return;
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("UPDATE mailqueue"
					+ " SET failcount = failcount + 1   WHERE mailid = :mailid");
			for (Mail mail : mails) {
				stmt.setString("mailid", mail.id);
				stmt.executeUpdate();
			}
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbMailQueue.markFailed()", e);
			throw e;
		}
	}

	public static void markSent(List<Mail> mails) throws SQLException {
		if (mails.isEmpty())
			return;
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("DELETE FROM mailqueue WHERE mailid = :mailid");
			for (Mail mail : mails) {
				stmt.setString("mailid", mail.id);
				stmt.executeUpdate();
			}
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbMailQueue.markFailed()", e);
			throw e;
		}
	}

}
