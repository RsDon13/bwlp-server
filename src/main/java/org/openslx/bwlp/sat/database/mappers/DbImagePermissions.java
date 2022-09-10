package org.openslx.bwlp.sat.database.mappers;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.bwlp.sat.database.Database;
import org.openslx.bwlp.sat.database.MysqlConnection;
import org.openslx.bwlp.sat.database.MysqlStatement;
import org.openslx.bwlp.sat.util.Sanitizer;
import org.openslx.bwlp.thrift.iface.ImagePermissions;

public class DbImagePermissions {

	private static final Logger LOGGER = LogManager.getLogger(DbImagePermissions.class);

	/**
	 * Build an instance of {@link ImagePermissions} by reading the given
	 * columns from the given {@link ResultSet}. If there are no permissions
	 * given in the ResultSet, <code>null</code> is returned.
	 * 
	 * @param rs the {@link ResultSet} to read from
	 * @param canLink Name of the column to read the "can link" permission from
	 * @param canDownload Name of the column to read the "can download"
	 *            permission from
	 * @param canEdit Name of the column to read the "can edit" permission from
	 * @param canAdmin Name of the column to read the "can admin" permission
	 *            from
	 * @return instance of {@link ImagePermissions}, or <code>null</code>
	 * @throws SQLException
	 */
	private static ImagePermissions fromResultSet(ResultSet rs, String canLink, String canDownload,
			String canEdit, String canAdmin) throws SQLException {
		byte link = rs.getByte(canLink);
		if (rs.wasNull())
			return null;
		return new ImagePermissions(link != 0, rs.getByte(canDownload) != 0, rs.getByte(canEdit) != 0,
				rs.getByte(canAdmin) != 0);
	}

	/**
	 * Build an instance of {@link ImagePermissions} by reading the
	 * columns <code>canlink</code>, <code>candownload</code>,
	 * <code>canedit</code>, <code>canadmin</code> from the given
	 * {@link ResultSet}. If there are no permissions
	 * given in the ResultSet, <code>null</code> is returned.
	 * 
	 * @param rs the {@link ResultSet} to read from
	 * @return instance of {@link ImagePermissions}, or <code>null</code>
	 * @throws SQLException
	 */
	public static ImagePermissions fromResultSetUser(ResultSet rs) throws SQLException {
		return fromResultSet(rs, "canlink", "candownload", "canedit", "canadmin");
	}

	/**
	 * Build an instance of {@link ImagePermissions} by reading the
	 * columns <code>canlinkdefault</code>, <code>candownloaddefault</code>,
	 * <code>caneditdefault</code>, <code>canadmindefault</code> from the given
	 * {@link ResultSet}. If there are no permissions
	 * given in the ResultSet, <code>null</code> is returned.
	 * 
	 * @param rs the {@link ResultSet} to read from
	 * @return instance of {@link ImagePermissions}, or <code>null</code>
	 * @throws SQLException
	 */
	public static ImagePermissions fromResultSetDefault(ResultSet rs) throws SQLException {
		return fromResultSet(rs, "canlinkdefault", "candownloaddefault", "caneditdefault", "canadmindefault");
	}

	/**
	 * Get permissions for the given image. IF <code>adminOnly</code> is true,
	 * only users with admin permissions will be returned.
	 * 
	 * @param imageBaseId UUID of image
	 * @param adminOnly Only return users with admin permission
	 * @return
	 * @throws SQLException
	 */
	public static Map<String, ImagePermissions> getForImageBase(String imageBaseId, boolean adminOnly)
			throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("SELECT userid, canlink, candownload, canedit, canadmin"
					+ " FROM imagepermission WHERE imagebaseid = :imagebaseid");
			stmt.setString("imagebaseid", imageBaseId);
			ResultSet rs = stmt.executeQuery();
			Map<String, ImagePermissions> list = new HashMap<>();
			while (rs.next()) {
				boolean admin = rs.getBoolean("canadmin");
				if (adminOnly && !admin)
					continue;
				ImagePermissions perm = new ImagePermissions(rs.getBoolean("canlink"),
						rs.getBoolean("candownload"), rs.getBoolean("canedit"), admin);
				list.put(rs.getString("userid"), perm);
			}
			return list;
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImagePermissions.getForImageBase()", e);
			throw e;
		}
	}

	public static void writeForImageBase(String imageBaseId, Map<String, ImagePermissions> permissions)
			throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("DELETE FROM imagepermission"
					+ " WHERE imagebaseid = :baseid");
			stmt.setString("baseid", imageBaseId);
			stmt.executeUpdate();
			stmt = connection.prepareStatement("INSERT INTO imagepermission"
					+ " (imagebaseid, userid, canlink, candownload, canedit, canadmin)"
					+ " VALUES (:baseid, :userid, :canlink, :candownload, :canedit, :canadmin)");
			stmt.setString("baseid", imageBaseId);
			for (Map.Entry<String, ImagePermissions> entry : permissions.entrySet()) {
				ImagePermissions perm = entry.getValue();
				perm = Sanitizer.handleImagePermissions(perm);
				stmt.setString("userid", entry.getKey());
				stmt.setBoolean("canlink", perm.link);
				stmt.setBoolean("candownload", perm.download);
				stmt.setBoolean("canedit", perm.edit);
				stmt.setBoolean("canadmin", perm.admin);
				stmt.executeUpdate();
			}
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImagePermissions.writeForImageBase()", e);
			throw e;
		}
	}

	public static void writeForImageBase(String imageBaseId, String userId, ImagePermissions imagePermissions) throws SQLException {
		Map<String, ImagePermissions> map = new HashMap<>();
		map.put(userId, imagePermissions);
		writeForImageBase(imageBaseId, map);
	}

}
