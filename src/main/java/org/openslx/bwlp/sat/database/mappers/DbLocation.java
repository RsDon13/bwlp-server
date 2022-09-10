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
import org.openslx.bwlp.sat.util.Configuration;
import org.openslx.bwlp.thrift.iface.Location;
import org.openslx.util.Util;

public class DbLocation {

	private static final Logger LOGGER = LogManager.getLogger(DbLocation.class);

	public static final List<Location> getLocations() throws SQLException {
		List<Location> list = new ArrayList<>();
		String locationsTable = Configuration.getDbLocationTable();
		if (Util.isEmptyString(locationsTable))
			return list;
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("SELECT locationid, parentlocationid, locationname FROM "
					+ locationsTable);
			ResultSet rs = stmt.executeQuery();
			while (rs.next()) {
				list.add(new Location(rs.getInt("locationid"), rs.getString("locationname"), rs.getInt("parentlocationid")));
			}
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbLocation.getLocations()", e);
			throw e;
		}
		return list;
	}

	public static List<Integer> getLectureLocations(MysqlConnection connection, String lectureId) throws SQLException {
		List<Integer> list = new ArrayList<>();
		String locationsTable = Configuration.getDbLocationTable();
		if (Util.isEmptyString(locationsTable))
			return list;
		MysqlStatement stmt = connection.prepareStatement("SELECT locationid FROM lecture_x_location"
				+ " WHERE lectureid = :lectureid");
		stmt.setString("lectureid", lectureId);
		ResultSet rs = stmt.executeQuery();
		while (rs.next()) {
			list.add(rs.getInt("locationid"));
		}
		return list;
	}

}
