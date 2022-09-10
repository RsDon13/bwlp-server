package org.openslx.bwlp.sat.database;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Updater {

	private static final Logger LOGGER = LogManager.getLogger(Updater.class);

	public static void updateDatabase() throws SQLException {
		addLocationPrivateField();
		addLectureLocationMapTable();
		addHasUsbAccessField();
		addLogTable();
		fixEmailFieldLength();
		addNetworkShares();
		addLectureFilter();
		addPredefinedFilters();
		addPredefinedNetworkShares();
		addPredefinedRunScripts();
		addPredefinedNetworkRules();
	}

	private static void addLectureLocationMapTable() throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			if (tableExists(connection, "lecture_x_location"))
				return;
			// Add table
			MysqlStatement tableAddStmt = connection.prepareStatement("CREATE TABLE `lecture_x_location` ("
					+ " `lectureid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
					+ " `locationid` int(11) NOT NULL,"
					+ " PRIMARY KEY (`lectureid`,`locationid`), KEY locationid (locationid)"
					+ " ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
			tableAddStmt.executeUpdate();
			// Add constraint
			MysqlStatement constraintStmt = connection.prepareStatement("ALTER TABLE `lecture_x_location`"
					+ " ADD FOREIGN KEY ( `lectureid` ) REFERENCES `sat`.`lecture` (`lectureid`)"
					+ " ON DELETE CASCADE ON UPDATE CASCADE");
			constraintStmt.executeUpdate();
			connection.commit();
			LOGGER.info("Updated database: Added lecture-location mapping table");
		} catch (SQLException e) {
			LOGGER.error("Query failed in Updater.addLectureLocationMapTable()", e);
			throw e;
		}
	}

	private static void addLocationPrivateField() throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			if (getColumnType(connection, "lecture", "islocationprivate") != null)
				return; // Field exists, don't do anything
			// Add field to table
			MysqlStatement columnAddStmt = connection.prepareStatement("ALTER TABLE lecture"
					+ " ADD islocationprivate TINYINT(1) UNSIGNED NOT NULL DEFAULT 0 AFTER isprivate");
			columnAddStmt.executeUpdate();
			connection.commit();
			LOGGER.info("Updated database: Added is location private field in lecture table");
		} catch (SQLException e) {
			LOGGER.error("Query failed in Updater.addLocationPrivateField()", e);
			throw e;
		}
	}

	private static void addHasUsbAccessField() throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			if (getColumnType(connection, "lecture", "hasusbaccess") != null)
				return; // Field exists, don't do anything
			// Add field to table
			MysqlStatement columnAddStmt = connection.prepareStatement("ALTER TABLE lecture"
					+ " ADD hasusbaccess TINYINT(1) UNSIGNED NOT NULL DEFAULT 1 AFTER hasinternetaccess");
			columnAddStmt.executeUpdate();
			connection.commit();
			LOGGER.info("Updated database: Added 'hasusbaccess' private field in lecture table");
		} catch (SQLException e) {
			LOGGER.error("Query failed in Updater.addHasUsbAccessField()", e);
			throw e;
		}
	}

	private static void addLogTable() throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			if (tableExists(connection, "actionlog"))
				return;
			// Add table
			MysqlStatement tableAddStmt = connection.prepareStatement("CREATE TABLE `actionlog` ("
					+ " `actionid` int(11) NOT NULL AUTO_INCREMENT,"
					+ " `dateline` bigint(20) NOT NULL,"
					+ " `userid` char(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,"
					+ " `targetid` char(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,"
					+ " `description` varchar(500) NOT NULL,"
					+ " PRIMARY KEY (`actionid`),"
					+ " KEY userid (userid, dateline),"
					+ " KEY targetid (targetid, dateline),"
					+ " KEY dateline (dateline)"
					+ " ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
			tableAddStmt.executeUpdate();
			// Add constraint
			MysqlStatement constraintStmt = connection.prepareStatement("ALTER TABLE `actionlog`"
					+ " ADD FOREIGN KEY ( `userid` ) REFERENCES `sat`.`user` (`userid`)"
					+ " ON DELETE SET NULL ON UPDATE CASCADE");
			constraintStmt.executeUpdate();
			connection.commit();
			LOGGER.info("Updated database: Added actionlog table");
		} catch (SQLException e) {
			LOGGER.error("Query failed in Updater.addLogTable()", e);
			throw e;
		}
	}

	/**
	 * Make email field longer. Was 50 chars, which is not enough in rare cases
	 * :)
	 */
	private static void fixEmailFieldLength() throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			if ("varchar(100)".equals(getColumnType(connection, "user", "email")))
				return; // Already 100 chars long, don'T do anything
			MysqlStatement upStmt = connection.prepareStatement("ALTER TABLE user CHANGE email"
					+ " email VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL");
			upStmt.executeUpdate();
			connection.commit();
			LOGGER.info("Updated database: Made email field longer");
		} catch (SQLException e) {
			LOGGER.error("Query failed in Updater.fixEmailFieldLength()", e);
			throw e;
		}
	}

	private static void addNetworkShares() throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			if (tableExists(connection, "networkshare"))
				return;
			// Add table
			MysqlStatement tableAddStmt = connection.prepareStatement(
					"CREATE TABLE `networkshare` ("
					+ " `lectureid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
					+ " `sharepresetid` int(11) NULL DEFAULT NULL,"
					+ " `sharedata` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,"
					+ " KEY `sharepresetid` (`sharepresetid`),"
					+ " KEY `fk_lectureid_1` (`lectureid`)"
					+ ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
			tableAddStmt.executeUpdate();
			// Add constraint
			MysqlStatement constraintStmt = connection.prepareStatement(
					"ALTER TABLE `networkshare` ADD CONSTRAINT `fk_lectureid_1`"
					+ " FOREIGN KEY (`lectureid`) REFERENCES `sat`.`lecture` (`lectureid`)"
					+ " ON DELETE CASCADE ON UPDATE CASCADE");
			constraintStmt.executeUpdate();
			connection.commit();
			LOGGER.info("Updated database: Added networkshare table");
		} catch (SQLException e) {
			LOGGER.error("Query failed in Updater.addNetworkShares()", e);
			throw e;
		}
	}

	private static void addLectureFilter() throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			if (tableExists(connection, "lecturefilter"))
				return;
			// Add table
			MysqlStatement tableAddStmt = connection.prepareStatement(
					"CREATE TABLE `lecturefilter` ("
					+ "  `lectureid` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
					+ "  `filtertype` varchar(24) CHARACTER SET ascii NULL DEFAULT NULL,"
					+ "  `filterkey` varchar(24) NULL DEFAULT NULL,"
					+ "  `filtervalue` varchar(200) NULL DEFAULT NULL,"
					+ "  KEY `lectureid` (`lectureid`,`filtertype`)"
					+ ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
			tableAddStmt.executeUpdate();
			// Add constraint
			MysqlStatement constraintStmt = connection.prepareStatement(
					"ALTER TABLE `lecturefilter` ADD "
					+ " CONSTRAINT `lectureid` FOREIGN KEY (`lectureid`) REFERENCES `lecture` (`lectureid`)"
					+ " ON DELETE CASCADE ON UPDATE CASCADE");
			constraintStmt.executeUpdate();
			connection.commit();
			LOGGER.info("Updated database: Added lecture filter table");
		} catch (SQLException e) {
			LOGGER.error("Query failed in Updater.addLectureFilter()", e);
			throw e;
		}
	}
	
	private static void addPredefinedFilters() throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			if (!tableExists(connection, "presetlecturefilter")) {
				// Add table
				MysqlStatement tableAddStmt = connection.prepareStatement("CREATE TABLE presetlecturefilter ("
						+ "      filterid int(11) NOT NULL AUTO_INCREMENT,"
						+ "      filtertype varchar(24) CHARACTER SET ascii NOT NULL,"
						+ "      filtername varchar(100) NOT NULL,"
						+ "      filterkey varchar(24) NOT NULL,"
						+ "      filtervalue varchar(200) NOT NULL,"
						+ "      PRIMARY KEY (filterid),"
						+ "      KEY (filtertype, filtername)"
						+ ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
				tableAddStmt.executeUpdate();
				LOGGER.info("Updated database: Added preset lecture filters");
			}
			if (getColumnType(connection, "lecturefilter", "filterpresetid") == null) {
				// Add column and constraint to lecturefilter
				MysqlStatement constraintStmt = connection.prepareStatement(
						"ALTER TABLE lecturefilter "
						+ " CHANGE filtertype filtertype VARCHAR(24) CHARACTER SET ascii NULL DEFAULT NULL,"
						+ " CHANGE filterkey filterkey VARCHAR(24) NULL DEFAULT NULL,"
						+ " CHANGE filtervalue filtervalue VARCHAR(200) NULL DEFAULT NULL,"
						+ " ADD COLUMN filterpresetid int(11) NULL DEFAULT NULL AFTER lectureid, "
						+ " ADD KEY filterpresetid (filterpresetid), "
						+ " ADD CONSTRAINT `filterpresetid` FOREIGN KEY (`filterpresetid`) REFERENCES `presetlecturefilter` (`filterid`)"
						+ "     ON DELETE CASCADE ON UPDATE CASCADE");
				constraintStmt.executeUpdate();
			}
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in Updater.addPredefinedFilters()", e);
			throw e;
		}
	}
	
	private static void addPredefinedNetworkShares() throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			if (!tableExists(connection, "presetnetworkshare")) {
				MysqlStatement addStmt = connection.prepareStatement("CREATE TABLE `presetnetworkshare` ("
						+ "  `shareid` int(11) NOT NULL AUTO_INCREMENT,"
						+ "  `sharename` varchar(100) NOT NULL,"
						+ "  `sharedata` varchar(500) NOT NULL,"
						+ "  `active` tinyint(1) NOT NULL DEFAULT '0',"
						+ "  PRIMARY KEY (`shareid`),"
						+ "  KEY sharename (`sharename`)"
						+ ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
				addStmt.executeUpdate();
				LOGGER.info("Updated database: Added preset network shares");
			}
			if (getColumnType(connection, "networkshare", "sharepresetid") == null) {
				MysqlStatement alterStmt = connection.prepareStatement("ALTER TABLE networkshare"
						+ " DROP COLUMN shareid, DROP COLUMN shareuid,"
						+ " ADD COLUMN sharepresetid int(11) NULL DEFAULT NULL,"
						+ " ADD KEY sharepresetid (sharepresetid),"
						+ " ADD CONSTRAINT sharepresetid FOREIGN KEY (sharepresetid) REFERENCES presetnetworkshare (shareid)"
						+ "     ON DELETE CASCADE ON UPDATE CASCADE");
				alterStmt.executeUpdate();
			}
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in Updater.addPredefinedNetworkShares()", e);
			throw e;
		}
	}
	
	/**
	 * Add tables for predefined runscripts. There's the main table for the
	 * scripts, then we need an n:m table to connect lectures to runscripts, and
	 * finally another n:m table to define which operating systems a script is
	 * suitable for.
	 */
	private static void addPredefinedRunScripts() throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			if (!tableExists(connection, "presetrunscript")) {
				connection.prepareStatement("CREATE TABLE presetrunscript ("
						+ "  runscriptid int(11) NOT NULL AUTO_INCREMENT,"
						+ "  scriptname varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,"
						+ "  content text COLLATE utf8mb4_unicode_ci NOT NULL,"
						+ "  extension varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL,"
						+ "  visibility tinyint(1) NOT NULL COMMENT '0 = hidden, 1 = normal, 2 = minimized',"
						+ "  passcreds tinyint(1) NOT NULL,"
						+ "  isglobal tinyint(1) NOT NULL COMMENT 'Whether to apply this script to all lectures',"
						+ "  PRIMARY KEY (runscriptid),"
						+ "  KEY isglobal (isglobal),"
						+ "  KEY scriptname (scriptname)"
						+ ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci")
						.executeUpdate();
				LOGGER.info("Updated database: Created table presetrunscript");
			}
			if (!tableExists(connection, "presetrunscript_x_operatingsystem")) {
				connection.prepareStatement("CREATE TABLE presetrunscript_x_operatingsystem ("
						+ "  runscriptid int(11) NOT NULL,"
						+ "  osid int(11) NOT NULL,"
						+ "  PRIMARY KEY (runscriptid, osid),"
						+ "  KEY osid (osid)"
						+ ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci")
						.executeUpdate();
				connection.prepareStatement(
						"ALTER TABLE presetrunscript_x_operatingsystem"
								+ "  ADD CONSTRAINT osid FOREIGN KEY (osid)"
								+ "    REFERENCES operatingsystem (osid)"
								+ "    ON DELETE CASCADE ON UPDATE CASCADE,"
								+ "  ADD CONSTRAINT runscriptid FOREIGN KEY (runscriptid)"
								+ "    REFERENCES presetrunscript (runscriptid)"
								+ "    ON DELETE CASCADE ON UPDATE CASCADE")
								.executeUpdate();
				LOGGER.info("Updated database: Created presetrunscript_x_operatingsystem table + constraint");
			}
			if (!tableExists(connection, "lecture_x_runscript")) {
				connection.prepareStatement("CREATE TABLE lecture_x_runscript ("
						+ "  lectureid char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
						+ "  runscriptid int(11) NOT NULL,"
						+ "  PRIMARY KEY (lectureid,runscriptid),"
						+ "  KEY runscriptid (runscriptid)"
						+ ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci")
						.executeUpdate();
				connection.prepareStatement(
						"ALTER TABLE lecture_x_runscript"
								+ "  ADD CONSTRAINT lecture_runscript FOREIGN KEY (lectureid)"
								+ "    REFERENCES lecture (lectureid)"
								+ "    ON DELETE CASCADE ON UPDATE CASCADE,"
								+ "  ADD CONSTRAINT lecture_x_runscript_ibfk_1 FOREIGN KEY (runscriptid)"
								+ "    REFERENCES presetrunscript (runscriptid)"
								+ "    ON DELETE CASCADE ON UPDATE CASCADE")
								.executeUpdate();
				LOGGER.info("Updated database: Created lecture_x_runscript table + constraint");
			}
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in addPredefinedRunScripts()", e);
			throw e;
		}
	}
	
	private static void addPredefinedNetworkRules() throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			if (!tableExists(connection, "presetnetworkrule")) {
				MysqlStatement addStmt = connection.prepareStatement("CREATE TABLE `presetnetworkrule` ("
						+ "  `ruleid` int(11) NOT NULL AUTO_INCREMENT,"
						+ "  `rulename` varchar(100) NOT NULL,"
						+ "  `ruledata` text NOT NULL,"
						+ "  PRIMARY KEY (`ruleid`),"
						+ "  KEY rulename (`rulename`)"
						+ ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
				addStmt.executeUpdate();
				LOGGER.info("Updated database: Added preset network rules");
			}
			if (!tableExists(connection, "lecture_x_networkrule")) {
				connection.prepareStatement("CREATE TABLE lecture_x_networkrule ("
						+ "  lectureid char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
						+ "  ruleid int(11) NOT NULL,"
						+ "  PRIMARY KEY (lectureid,ruleid),"
						+ "  KEY ruleid (ruleid)"
						+ ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci")
						.executeUpdate();
				connection.prepareStatement(
						"ALTER TABLE lecture_x_networkrule"
								+ "  ADD CONSTRAINT lecture_x_networkrule_ibfk_1 FOREIGN KEY (lectureid)"
								+ "    REFERENCES lecture (lectureid)"
								+ "    ON DELETE CASCADE ON UPDATE CASCADE,"
								+ "  ADD CONSTRAINT lecture_x_networkrule_ibfk_2 FOREIGN KEY (ruleid)"
								+ "    REFERENCES presetnetworkrule (ruleid)"
								+ "    ON DELETE CASCADE ON UPDATE CASCADE")
								.executeUpdate();
				LOGGER.info("Updated database: Created lecture_x_networkrule table + constraint");
			}
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in Updater.addPredefinedNetworkShares()", e);
			throw e;
		}
	}
	
	/*
	 * Helper methods
	 */

	/**
	 * Check whether given table exists.
	 * @throws SQLException
	 */
	private static boolean tableExists(MysqlConnection connection, String name) throws SQLException {
		MysqlStatement tablesStmt = connection.prepareStatement("SHOW TABLES");
		ResultSet tables = tablesStmt.executeQuery();
		boolean exists = false;
		while (tables.next()) {
			if (tables.getString(1).equals(name)) {
				exists = true;
				break;
			}
		}
		tablesStmt.close();
		return exists;
	}
	
	/**
	 * Return Type string of given column. Returns null if column doesn't exist,
	 * so this can also be used to check for column existence in a table. Note
	 * that a nonexistent table is considered an error and generates an
	 * exception.
	 * 
	 * @throws SQLException
	 */
	private static String getColumnType(MysqlConnection connection, String table, String column)
			throws SQLException {
		MysqlStatement checkStmt = connection.prepareStatement("DESCRIBE " + table);
		ResultSet cols = checkStmt.executeQuery();
		String ret = null;
		while (cols.next()) {
			if (cols.getString("Field").equals(column)) {
				ret = cols.getString("Type");
				break;
			}
		}
		return ret;
	}

}
