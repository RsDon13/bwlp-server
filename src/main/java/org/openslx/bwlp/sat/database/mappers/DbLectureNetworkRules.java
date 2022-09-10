package org.openslx.bwlp.sat.database.mappers;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.bwlp.sat.database.Database;
import org.openslx.bwlp.sat.database.MysqlConnection;
import org.openslx.bwlp.sat.database.MysqlStatement;
import org.openslx.bwlp.thrift.iface.NetRule;
import org.openslx.bwlp.thrift.iface.PresetNetRule;
import org.openslx.util.Json;

public class DbLectureNetworkRules {
	
	private static final Logger LOGGER = LogManager.getLogger(DbLectureNetworkRules.class);

	public static void writeLectureNetworkExceptions(MysqlConnection connection, String lectureId,
			List<Integer> ruleIds) throws SQLException {
		if (lectureId == null || lectureId.isEmpty()) {
			return;
		}
		MysqlStatement delStmt = connection
				.prepareStatement("DELETE FROM lecture_x_networkrule WHERE lectureid = :lectureid");
		delStmt.setString("lectureid", lectureId);
		delStmt.executeUpdate();
		if (ruleIds == null || ruleIds.isEmpty()) {
			return;
		}
		MysqlStatement addStmt = connection
				.prepareStatement("INSERT IGNORE INTO lecture_x_networkrule (lectureid, ruleid)"
						+ " VALUES (:lectureid, :ruleid)");
		addStmt.setString("lectureid", lectureId);
		for (int ruleId : ruleIds) {
			addStmt.setInt("ruleid", ruleId);
			addStmt.executeUpdate();
		}
	}

	static List<NetRule> getForStartup(MysqlConnection connection, String lectureId) throws SQLException {
		MysqlStatement stmt = connection.prepareStatement("SELECT p.ruledata FROM presetnetworkrule p"
				+ " INNER JOIN `lecture_x_networkrule` lxr"
				+ " ON (lxr.lectureid = :lectureid AND lxr.ruleid = p.ruleid)");
		stmt.setString("lectureid", lectureId);
		ResultSet rs = stmt.executeQuery();
		List<NetRule> result = new ArrayList<>();
		while (rs.next()) {
			NetRule[] lst = Json.deserialize(rs.getString("ruledata"), NetRule[].class);
			if (lst == null)
				continue;
			for (NetRule r : lst) {
				result.add(r);
			}
		}
		return result;
	}
	
	public static List<Integer> getForEdit(MysqlConnection connection, String lectureId) throws SQLException
	{
		MysqlStatement stmt = connection.prepareStatement("SELECT ruleid FROM lecture_x_networkrule"
				+ " WHERE lectureid = :lectureid");
		stmt.setString("lectureid", lectureId);
		ResultSet rs = stmt.executeQuery();
		List<Integer> result = new ArrayList<>();
		while (rs.next()) {
			result.add(rs.getInt("ruleid"));
		}
		return result;
	}

	public static List<PresetNetRule> getPredefined() throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			List<PresetNetRule> list = new ArrayList<>();
			MysqlStatement stmt = connection
					.prepareStatement("SELECT ruleid, rulename, ruledata"
							+ " FROM presetnetworkrule");
			ResultSet rs = stmt.executeQuery();
			while (rs.next()) {
				NetRule[] ruleArray = Json.deserialize(rs.getString("ruledata"), NetRule[].class);
				List<NetRule> rules;
				if (ruleArray == null || ruleArray.length == 0) {
					rules = new ArrayList<>(0);
				} else {
					rules = Arrays.asList(ruleArray);
				}
				list.add(new PresetNetRule(rs.getInt("ruleid"), rs.getString("rulename"), rules));
			}
			return list;
		} catch (SQLException e) {
			LOGGER.error("Query failed in getPredefinedNetshares()", e);
			throw e;
		}
	}

}
