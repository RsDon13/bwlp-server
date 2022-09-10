package org.openslx.bwlp.sat.database.mappers;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.bwlp.sat.RuntimeConfig;
import org.openslx.bwlp.sat.database.Database;
import org.openslx.bwlp.sat.database.MysqlConnection;
import org.openslx.bwlp.sat.database.MysqlStatement;
import org.openslx.bwlp.sat.database.Paginator;
import org.openslx.bwlp.sat.database.models.LocalUser;
import org.openslx.bwlp.thrift.iface.SatelliteUserConfig;
import org.openslx.bwlp.thrift.iface.TNotFoundException;
import org.openslx.bwlp.thrift.iface.UserInfo;
import org.openslx.util.QuickTimer;
import org.openslx.util.QuickTimer.Task;
import org.openslx.util.TimeoutHashMap;
import org.openslx.util.Util;

public class DbUser {

	private static boolean legacyUsersExist = false;

	public static class User {
		public final UserInfo ui;
		public final LocalUser local;

		public User(UserInfo ui, LocalUser local) {
			this.ui = ui;
			this.local = local;
		}
	}

	private static final Logger LOGGER = LogManager.getLogger(DbUser.class);

	private static Map<String, User> userCache = new TimeoutHashMap<>(TimeUnit.MINUTES.toMillis(15));

	/**
	 * Get all users, starting at page <code>page</code>.
	 * This function will return a maximum of {@link #PER_PAGE} results, so
	 * you might need to call this method several times.
	 * 
	 * @param page Page to return. The first page is page 0.
	 * @return List of {@link UserInfo}
	 * @throws SQLException
	 */
	public static List<UserInfo> getAll(int page) throws SQLException {
		if (page < 0)
			return new ArrayList<>(1);
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("SELECT userid, firstname, lastname, email, organizationid"
					+ " FROM user ORDER BY userid ASC " + Paginator.limitStatement(page));
			ResultSet rs = stmt.executeQuery();
			List<UserInfo> list = new ArrayList<>();
			while (rs.next()) {
				list.add(new UserInfo(rs.getString("userid"), rs.getString("firstname"),
						rs.getString("lastname"), rs.getString("email"), rs.getString("organizationid")));
			}
			return list;
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbUser.getAll()", e);
			throw e;
		}
	}

	public static UserInfo getOrNull(String userId) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("SELECT userid, firstname, lastname, email, organizationid"
					+ " FROM user WHERE userid = :userid");
			stmt.setString("userid", userId);
			ResultSet rs = stmt.executeQuery();
			if (rs.next()) {
				return new UserInfo(rs.getString("userid"), rs.getString("firstname"),
						rs.getString("lastname"), rs.getString("email"), rs.getString("organizationid"));
			}
			return null;
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbUser.getAll()", e);
			throw e;
		}
	}

	/**
	 * Get local-only information for given user.
	 * 
	 * @param user {@link UserInfo} instance representing the user
	 * @return {@link LocalUser} instance matching the given user, or null if
	 *         not found
	 * @throws SQLException
	 */
	public static LocalUser getLocalData(UserInfo user) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("SELECT lastlogin, canlogin, issuperuser, emailnotifications"
					+ " FROM user WHERE userid = :userid LIMIT 1");
			stmt.setString("userid", user.userId);
			ResultSet rs = stmt.executeQuery();
			if (!rs.next())
				return null;
			return new LocalUser(rs.getLong("lastlogin"), rs.getBoolean("canlogin"),
					rs.getBoolean("issuperuser"), rs.getBoolean("emailnotifications"));
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbUser.getLocalData()", e);
			throw e;
		}
	}

	public static void writeUserConfig(UserInfo user, SatelliteUserConfig config) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("UPDATE user SET"
					+ " emailnotifications = :emailnotifications    WHERE userid = :userid");
			stmt.setString("userid", user.userId);
			stmt.setBoolean("emailnotifications", config.emailNotifications);
			stmt.executeUpdate();
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbUser.writeUserConfig()", e);
			throw e;
		}
	}

	/**
	 * Insert given user into db (if not already existent), otherwise just
	 * update the "lastlogin" field.
	 * 
	 * @param ui {@link UserInfo}
	 * @throws SQLException
	 */
	public static void writeUserOnLogin(UserInfo ui) throws SQLException {
		writeUser(ui, true);
	}

	public static void writeUserOnReplication(UserInfo ui) throws SQLException {
		writeUser(ui, false);
	}

	private static void writeUser(UserInfo ui, boolean isLogin) throws SQLException {
		// TODO: Ugly hardcode solution - should be queried from DB, with a nice helper class
		if (ui.firstName.length() > 50) {
			ui.firstName = ui.firstName.substring(0, 50);
		}
		if (ui.lastName.length() > 50) {
			ui.lastName = ui.lastName.substring(0, 50);
		}
		if (ui.eMail.length() > 100) {
			ui.eMail = ui.eMail.substring(0, 100);
		}
		boolean recheckLegacy = true;
		try (MysqlConnection connection = Database.getConnection()) {
			if (!legacyUsersExist || !tryLegacyUserUpdate(connection, ui)) {
				// No legacy messed up account found - use normal way
				MysqlStatement insUpStmt;
				if (isLogin) {
					insUpStmt = connection.prepareStatement("INSERT INTO user"
							+ " (userid, firstname, lastname, email, organizationid, lastlogin, canlogin, issuperuser, emailnotifications)"
							+ " VALUES"
							+ " (:userid, :firstname, :lastname, :email, :organizationid, UNIX_TIMESTAMP(), :canlogin, 0, 1)"
							+ " ON DUPLICATE KEY UPDATE lastlogin = UNIX_TIMESTAMP(), email = VALUES(email),"
							+ " firstname = VALUES(firstname), lastname = VALUES(lastname), organizationid = VALUES(organizationid)");
					insUpStmt.setBoolean("canlogin", RuntimeConfig.allowLoginByDefault());
				} else {
					insUpStmt = connection.prepareStatement("INSERT INTO user"
							+ " (userid, firstname, lastname, email, organizationid, canlogin, issuperuser, emailnotifications)"
							+ " VALUES"
							+ " (:userid, :firstname, :lastname, :email, :organizationid, 0, 0, 0)"
							+ " ON DUPLICATE KEY UPDATE email = VALUES(email),"
							+ " firstname = VALUES(firstname), lastname = VALUES(lastname), organizationid = VALUES(organizationid)");
				}
				insUpStmt.setString("userid", ui.userId);
				insUpStmt.setString("firstname", ui.firstName);
				insUpStmt.setString("lastname", ui.lastName);
				insUpStmt.setString("email", ui.eMail);
				insUpStmt.setString("organizationid", ui.organizationId);
				insUpStmt.executeUpdate();
				recheckLegacy = false;
			}
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbUser.writeUserOnLogin()", e);
			throw e;
		}
		if (recheckLegacy) {
			// Run check again
			checkIfLegacyUsersExist();
		}
	}

	/**
	 * Try to update a legacy imported user and fix the entry
	 */
	private static boolean tryLegacyUserUpdate(MysqlConnection connection, UserInfo ui) throws SQLException {
		// Well... don't look at the code below. The old server had a bug where it wrote
		// wrong user ids to the data base. If we imported old data, the user info table
		// might contain a messed up entry for the given user. So instead of a nice
		// INSERT ... ON DUPLICATE KEY we have to do this funny stuff.
		MysqlStatement findStmt = connection.prepareStatement("SELECT userid FROM user"
				+ " WHERE firstname = :firstname AND lastname = :lastname AND email = :email");
		findStmt.setString("firstname", ui.firstName);
		findStmt.setString("lastname", ui.lastName);
		findStmt.setString("email", "@" + ui.eMail + "@");
		ResultSet rs = findStmt.executeQuery();
		if (!rs.next())
			return false;
		// We actually found an old imported entry - just update
		String oldId = rs.getString("userid");
		MysqlStatement insUpStmt = connection.prepareStatement("UPDATE IGNORE user"
				+ " SET lastlogin = UNIX_TIMESTAMP(), email = :email, userid = :newuserid, organizationid = :organizationid,"
				+ " emailnotifications = 1 WHERE userid = :olduserid");
		insUpStmt.setString("newuserid", ui.userId);
		insUpStmt.setString("email", ui.eMail);
		insUpStmt.setString("organizationid", ui.organizationId);
		insUpStmt.setString("olduserid", oldId);
		insUpStmt.executeUpdate();
		if (!ui.userId.equals(oldId)) {
			// Be extra safe: in case the update failed (dup key?) we patch the old entry so it doesn't look like an old one anymore
			MysqlStatement fixStmt = connection.prepareStatement("UPDATE user SET"
					+ " email = 'void', emailnotifications = 0 WHERE userid = :olduserid AND email LIKE '@%'");
			fixStmt.setString("olduserid", oldId);
			fixStmt.executeUpdate();
		}
		return true;
	}

	public static void checkIfLegacyUsersExist() {
		QuickTimer.scheduleOnce(new Task() {
			@Override
			public void fire() {
				try (MysqlConnection connection = Database.getConnection()) {
					MysqlStatement stmt = connection.prepareStatement("SELECT userid FROM user"
							+ " WHERE email LIKE '@%@' LIMIT 1");
					ResultSet rs = stmt.executeQuery();
					legacyUsersExist = rs.next();
				} catch (SQLException e) {
					LOGGER.error("Query failed in DbUser.checkIfLegacyUsersExist()", e);
				}
				LOGGER.info("Imported legacy users exist: " + Boolean.toString(legacyUsersExist));
			}
		});
	}

	public static User getCached(String userId) throws SQLException, TNotFoundException {
		synchronized (DbUser.class) {
			User user = userCache.get(userId);
			if (user != null)
				return user;
		}
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("SELECT userid, firstname, lastname, email, organizationid,"
					+ " lastlogin, canlogin, issuperuser, emailnotifications"
					+ " FROM user WHERE userid = :userid");
			stmt.setString("userid", userId);
			ResultSet rs = stmt.executeQuery();
			if (!rs.next())
				throw new TNotFoundException();
			UserInfo userInfo = new UserInfo(rs.getString("userid"), rs.getString("firstname"),
					rs.getString("lastname"), rs.getString("email"), rs.getString("organizationid"));
			LocalUser local = new LocalUser(rs.getLong("lastlogin"), rs.getBoolean("canlogin"),
					rs.getBoolean("issuperuser"), rs.getBoolean("emailnotifications"));
			User user = new User(userInfo, local);
			synchronized (DbUser.class) {
				userCache.put(userInfo.userId, user);
			}
			return user;
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbUser.getCached()", e);
			throw e;
		}
	}

	/**
	 * @return list of users who didn't log in for at least 180 days
	 */
	public static List<UserInfo> getInactive() throws SQLException {
		long cutoff = Util.unixTime() - TimeUnit.DAYS.toSeconds(180);
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("SELECT userid, firstname, lastname, email, organizationid"
					+ " FROM user WHERE lastlogin < :cutoff AND canlogin <> 0 AND issuperuser = 0");
			stmt.setLong("cutoff", cutoff);
			ResultSet rs = stmt.executeQuery();
			List<UserInfo> list = new ArrayList<>();
			while (rs.next()) {
				list.add(new UserInfo(rs.getString("userid"), rs.getString("firstname"),
						rs.getString("lastname"), rs.getString("email"), rs.getString("organizationid")));
			}
			return list;
		} catch (SQLException e) {
			LOGGER.error("Query failed in getInactive()", e);
			throw e;
		}
	}

	/**
	 * Delete given user from database. Not that this might fail due to
	 * constraints.
	 * 
	 * @param user the user to delete
	 */
	public static boolean deleteUser(UserInfo user) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("DELETE FROM user WHERE userid = :userid");
			stmt.setString("userid", user.userId);
			try {
				int num = stmt.executeUpdate();
				connection.commit();
				return num > 0;
			} catch (SQLException e) {
				connection.rollback();
				return false;
			}
		} catch (SQLException e) {
			LOGGER.error("Query failed in deleteUser()", e);
			throw e;
		}
	}

}
