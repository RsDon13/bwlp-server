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

public class DbSoftwareTag {

	private static final Logger LOGGER = LogManager.getLogger(DbSoftwareTag.class);

	/**
	 * Get list of software installed in a certain image version.
	 * 
	 * @param connection database connection to use
	 * @param imageVersionId UUID of image version
	 * @return list of software products
	 * @throws SQLException
	 */
	public static List<String> getImageVersionSoftwareList(MysqlConnection connection, String imageVersionId)
			throws SQLException {
		MysqlStatement stmt = connection.prepareStatement("SELECT softwarestring FROM software"
				+ " INNER JOIN imageversion_x_software USING (softwareid)"
				+ " WHERE imageversionid = :imageversionid");
		stmt.setString("imageversionid", imageVersionId);
		ResultSet rs = stmt.executeQuery();
		List<String> softwareList = new ArrayList<>();
		while (rs.next()) {
			softwareList.add(rs.getString("softwarestring"));
		}
		stmt.close();
		return softwareList;
	}

	/**
	 * Get list of software installed in a certain image version.
	 * 
	 * @param imageVersionId UUID of image version
	 * @return list of software products
	 * @throws SQLException
	 */
	public static List<String> getImageVersionSoftwareList(String imageVersionId) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			return getImageVersionSoftwareList(connection, imageVersionId);
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbSoftware.getImageVersionSoftwareList()", e);
			throw e;
		}
	}
	
	public static List<String> getImageTags(MysqlConnection connection, String imageBaseId) throws SQLException {
		MysqlStatement stmt = connection.prepareStatement("SELECT tagname FROM imagetag"
				+ " WHERE imagebaseid = :imagebaseid");
		stmt.setString("imagebaseid", imageBaseId);
		ResultSet rs = stmt.executeQuery();
		List<String> tagList = new ArrayList<>();
		while (rs.next()) {
			tagList.add(rs.getString("displayname"));
		}
		stmt.close();
		return tagList;
	}

}
