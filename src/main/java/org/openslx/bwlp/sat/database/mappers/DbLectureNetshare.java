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
import org.openslx.bwlp.thrift.iface.NetShare;
import org.openslx.util.Json;
import org.openslx.util.Util;

public class DbLectureNetshare {

	private static final Logger LOGGER = LogManager.getLogger(DbLectureNetshare.class);

	public static void writeForLecture(MysqlConnection connection, String lectureId, List<NetShare> shares)
			throws SQLException {
		if (lectureId == null || lectureId.isEmpty()) {
			return;
		}
		MysqlStatement delStmt = connection
				.prepareStatement("DELETE FROM networkshare WHERE lectureid = :lectureid");
		delStmt.setString("lectureid", lectureId);
		delStmt.executeUpdate();
		if (shares == null || shares.isEmpty()) {
			return;
		}
		MysqlStatement addCustomStmt = connection
				.prepareStatement("INSERT IGNORE INTO networkshare (lectureid, sharedata)"
						+ " VALUES (:lectureid, :sharedata)");
		MysqlStatement addPresetStmt = connection
				.prepareStatement("INSERT IGNORE INTO networkshare (lectureid, sharepresetid)"
						+ " VALUES (:lectureid, :shareid)");
		addCustomStmt.setString("lectureid", lectureId);
		addPresetStmt.setString("lectureid", lectureId);
		for (NetShare share : shares) {
			if (share.shareId > 0) {
				// Preset
				addPresetStmt.setInt("shareid", share.shareId);
				addPresetStmt.executeUpdate();
			} else {
				// Custom
				if (Util.isEmptyString(share.path) || Util.isEmptyString(share.mountpoint))
					continue;
				String netshareJson = Json.serialize(share);
				addCustomStmt.setString("sharedata", netshareJson);
				addCustomStmt.executeUpdate();
			}
		}
	}

	public static List<NetShare> getCombinedForLecture(MysqlConnection connection, String lectureId)
			throws SQLException {
		List<NetShare> list = new ArrayList<>();
		MysqlStatement netsharestmt = connection.prepareStatement(
				"SELECT pns.shareid, IFNULL(pns.sharedata, ns.sharedata) AS sharedata FROM networkshare ns"
						+ " LEFT JOIN presetnetworkshare pns ON (ns.sharepresetid = pns.shareid)"
						+ " WHERE ns.lectureid = :lectureid AND (pns.active IS NULL OR pns.active <> 0)");
		netsharestmt.setString("lectureid", lectureId);
		ResultSet rs = netsharestmt.executeQuery();
		while (rs.next()) {
			list.add(Json.deserialize(rs.getString("sharedata"), NetShare.class)
					.setShareId(rs.getInt("shareid")));
		}
		return list;
	}

	public static void getSplitForLecture(MysqlConnection connection, String lectureId,
			List<NetShare> custom, List<Integer> predef) throws SQLException {
		MysqlStatement netsharestmt = connection
				.prepareStatement("SELECT sharepresetid, sharedata FROM networkshare WHERE lectureid = :lectureid");
		netsharestmt.setString("lectureid", lectureId);
		ResultSet rs = netsharestmt.executeQuery();
		while (rs.next()) {
			int id = rs.getInt("sharepresetid");
			if (id == 0) {
				custom.add(Json.deserialize(rs.getString("sharedata"), NetShare.class).setShareId(0));
			} else {
				predef.add(id);
			}
		}
	}

	public static List<NetShare> getPredefined() throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			List<NetShare> list = new ArrayList<>();
			MysqlStatement stmt = connection
					.prepareStatement("SELECT shareid, sharedata" + " FROM presetnetworkshare");
			ResultSet rs = stmt.executeQuery();
			while (rs.next()) {
				list.add(Json.deserialize(rs.getString("sharedata"), NetShare.class)
						.setShareId(rs.getInt("shareid")));
			}
			return list;
		} catch (SQLException e) {
			LOGGER.error("Query failed in getPredefinedNetshares()", e);
			throw e;
		}
	}

}
