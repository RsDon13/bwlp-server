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
import org.openslx.bwlp.sat.web.XmlFilterEntry;
import org.openslx.bwlp.thrift.iface.LdapFilter;
import org.openslx.util.Util;

public class DbLectureFilter {

	private static final Logger LOGGER = LogManager.getLogger(DbLectureFilter.class);
	
	private static MysqlStatement getLdapFilterStatement(MysqlConnection connection, String lectureId) throws SQLException {
		MysqlStatement stmt = connection.prepareStatement(
				"SELECT p.filterid, p.filtername,"
				+ " IFNULL(p.filterkey, f.filterkey) AS filterkey, IFNULL(p.filtervalue, f.filtervalue) AS filtervalue"
				+ " FROM lecturefilter f"
				+ " LEFT JOIN presetlecturefilter p ON (f.filterpresetid = p.filterid)"
				+ " WHERE f.lectureid = :lectureid AND (f.filtertype = 'LDAP' OR p.filtertype = 'LDAP')");
		stmt.setString("lectureid", lectureId);
		return stmt;
	}

	public static void getSplitForLectureLdap(MysqlConnection connection, String lectureId,
			List<LdapFilter> custom, List<Integer> predef) throws SQLException {
		MysqlStatement stmt = getLdapFilterStatement(connection, lectureId);
		ResultSet rs = stmt.executeQuery();
		while (rs.next()) {
			int id = rs.getInt("filterid");
			if (id != 0) {
				predef.add(id);
			} else {
				LdapFilter filter = new LdapFilter(rs.getString("filterkey"), rs.getString("filtervalue"));
				filter.setFilterId(id);
				filter.setTitle(rs.getString("filtername"));
				custom.add(filter);
			}
		}
	}
	
	public static List<LdapFilter> getPredefinedLdap()
			throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			List<LdapFilter> list = new ArrayList<>();
			MysqlStatement stmt = connection.prepareStatement("SELECT filterid, filtername, filterkey, filtervalue"
					+ " FROM presetlecturefilter WHERE filtertype = 'LDAP'");
			ResultSet rs = stmt.executeQuery();
			while (rs.next()) {
				LdapFilter filter = new LdapFilter(rs.getString("filterkey"), rs.getString("filtervalue"));
				filter.setFilterId(rs.getInt("filterid"));
				filter.setTitle(rs.getString("filtername"));
				list.add(filter);
			}
			return list;
		} catch (SQLException e) {
			LOGGER.error("Query failed in getPredefinedLdapFilters()", e);
			throw e;
		}
	}

	public static final List<XmlFilterEntry> getFiltersXml(MysqlConnection connection, String lectureId)
			throws SQLException {
		List<XmlFilterEntry> list = null;
		MysqlStatement stmt = getLdapFilterStatement(connection, lectureId);
		ResultSet rs = stmt.executeQuery();
		while (rs.next()) {
			String key = rs.getString("filterkey");
			String value = rs.getString("filtervalue");
			if (list == null) {
				list = new ArrayList<>();
			}
			list.add(new XmlFilterEntry("LDAP", key, value));
		}
		return list;
	}

	public static void writeForLectureLdap(MysqlConnection connection, String lectureId, List<LdapFilter> list)
			throws SQLException {
		if (lectureId == null || lectureId.isEmpty()) {
			return;
		}
		MysqlStatement delStmt = connection.prepareStatement("DELETE FROM lecturefilter WHERE lectureid = :lectureid");
		delStmt.setString("lectureid", lectureId);
		delStmt.executeUpdate();
		if (list == null || list.isEmpty()) {
			return;
		}
		MysqlStatement addCustomStmt = connection.prepareStatement(
				"INSERT INTO lecturefilter (lectureid, filtertype, filterkey, filtervalue)"
				+ " VALUES (:lectureid, 'LDAP', :key, :value)");
		MysqlStatement addPredefStmt = connection.prepareStatement(
				"INSERT INTO lecturefilter (lectureid, filterpresetid)"
				+ " VALUES (:lectureid, :filterid)");
		addCustomStmt.setString("lectureid", lectureId);
		addPredefStmt.setString("lectureid", lectureId);
		for (LdapFilter filter : list) {
			if (filter.filterId == 0) {
				// Custom
				if (Util.isEmptyString(filter.attribute) || filter.value == null)
					continue;
				addCustomStmt.setString("key", filter.attribute);
				addCustomStmt.setString("value", filter.value);
				addCustomStmt.executeUpdate();
			} else {
				// Predef reference
				addPredefStmt.setInt("filterid", filter.filterId);
				addPredefStmt.executeUpdate();
			}
		}
	}

}
