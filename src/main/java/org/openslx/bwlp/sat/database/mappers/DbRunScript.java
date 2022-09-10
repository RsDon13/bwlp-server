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
import org.openslx.bwlp.thrift.iface.PresetRunScript;
import org.openslx.util.Util;

public class DbRunScript {

	private static final Logger LOGGER = LogManager.getLogger(DbRunScript.class);
	
	public static void writeLectureRunScripts(MysqlConnection connection, String lectureId, List<Integer> scripts)
			throws SQLException {
		if (lectureId == null || lectureId.isEmpty()) {
			return;
		}
		MysqlStatement delStmt = connection
				.prepareStatement("DELETE FROM lecture_x_runscript WHERE lectureid = :lectureid");
		delStmt.setString("lectureid", lectureId);
		delStmt.executeUpdate();
		if (scripts == null || scripts.isEmpty())
			return;
		MysqlStatement addStmt = connection
				.prepareStatement("INSERT IGNORE INTO lecture_x_runscript (lectureid, runscriptid)"
						+ " VALUES (:lectureid, :scriptid)");
		addStmt.setString("lectureid", lectureId);
		for (Integer id : scripts) {
			if (id == null)
				continue;
			addStmt.setInt("scriptid", id);
			addStmt.executeUpdate();
		}
	}

	static List<DbLecture.RunScript> getRunScriptsForLaunch(MysqlConnection connection, String lectureId,
			int osId) {
		List<DbLecture.RunScript> retval = null;
		try {
			MysqlStatement stmt = connection.prepareStatement("SELECT us.content, us.extension, us.visibility, us.passcreds"
					+ " FROM (SELECT s.scriptname, s.content, s.extension, s.visibility, s.passcreds, s.runscriptid"
					+ "       FROM presetrunscript s"
					+ "       INNER JOIN lecture_x_runscript lxr ON (lxr.lectureid = :lectureid AND lxr.runscriptid = s.runscriptid)"
					+ "    UNION SELECT t.scriptname, t.content, t.extension, t.visibility, t.passcreds, t.runscriptid"
					+ "       FROM presetrunscript t WHERE t.isglobal"
					+ " ) us INNER JOIN presetrunscript_x_operatingsystem pxo ON (pxo.runscriptid = us.runscriptid AND pxo.osid = :osid)"
					+ " ORDER BY us.scriptname ASC");
			stmt.setString("lectureid", lectureId);
			stmt.setInt("osid", osId);
			ResultSet rs = stmt.executeQuery();
			while (rs.next()) {
				if (retval == null) {
					retval = new ArrayList<>();
				}
				retval.add(new DbLecture.RunScript(rs.getString("content"), rs.getString("extension"),
						rs.getInt("visibility"), rs.getBoolean("passcreds")));
			}
		} catch (SQLException e) {
			// DERP
		}
		return retval;
	}

	public static List<PresetRunScript> getPredefinedRunScripts() throws SQLException {
		List<PresetRunScript> list = new ArrayList<>();
		try (MysqlConnection connection = Database.getConnection()) {
			ResultSet rs = connection.prepareStatement(
					"SELECT s.runscriptid, s.scriptname,"
							+ " Group_Concat(sxo.osid) AS osids FROM presetrunscript s"
							+ " INNER JOIN presetrunscript_x_operatingsystem sxo USING (runscriptid)"
							+ " WHERE isglobal = 0                          "
							+ " GROUP BY runscriptid").executeQuery();
			while (rs.next()) {
				list.add(new PresetRunScript(rs.getInt("runscriptid"), rs.getString("scriptname"),
						splitStringToInt(rs.getString("osids"))));
			}
		} catch (SQLException e) {
			LOGGER.error("Query failed in getPredefinedRunScripts()", e);
			throw e;
		}
		return list;
	}
	
	public static List<Integer> getForEdit(MysqlConnection connection, String lectureId) throws SQLException
	{
		MysqlStatement stmt = connection.prepareStatement("SELECT runscriptid FROM lecture_x_runscript"
				+ " WHERE lectureid = :lectureid");
		stmt.setString("lectureid", lectureId);
		ResultSet rs = stmt.executeQuery();
		List<Integer> result = new ArrayList<>();
		while (rs.next()) {
			result.add(rs.getInt("runscriptid"));
		}
		return result;
	}

	private static List<Integer> splitStringToInt(String input) {
		if (input == null)
			return new ArrayList<>(0);
		String[] parts = input.split(",");
		List<Integer> list = new ArrayList<>(parts.length);
		for (String s : parts) {
			int i = Util.parseInt(s, -1);
			if (i == -1)
				continue;
			list.add(i);
		}
		return list;
	}

}
