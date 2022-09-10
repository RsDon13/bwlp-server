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
import org.openslx.bwlp.thrift.iface.LecturePermissions;

public class DbLecturePermissions {

	private static final Logger LOGGER = LogManager.getLogger(DbLecturePermissions.class);

	/**
	 * Build an instance of {@link LecturePermissions} by reading the given
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
	 * @return instance of {@link LecturePermissions}, or <code>null</code>
	 * @throws SQLException
	 */
	private static LecturePermissions fromResultSet(ResultSet rs, String canEdit, String canAdmin)
			throws SQLException {
		boolean edit = rs.getBoolean(canEdit);
		if (rs.wasNull())
			return null;
		return new LecturePermissions(edit, rs.getBoolean(canAdmin));
	}

	/**
	 * Build an instance of {@link LecturePermissions} by reading the
	 * columns <code>canlink</code>, <code>candownload</code>,
	 * <code>canedit</code>, <code>canadmin</code> from the given
	 * {@link ResultSet}. If there are no permissions
	 * given in the ResultSet, <code>null</code> is returned.
	 * 
	 * @param rs the {@link ResultSet} to read from
	 * @return instance of {@link LecturePermissions}, or <code>null</code>
	 * @throws SQLException
	 */
	public static LecturePermissions fromResultSetUser(ResultSet rs) throws SQLException {
		return fromResultSet(rs, "canedit", "canadmin");
	}

	/**
	 * Build an instance of {@link LecturePermissions} by reading the
	 * columns <code>canlinkdefault</code>, <code>candownloaddefault</code>,
	 * <code>caneditdefault</code>, <code>canadmindefault</code> from the given
	 * {@link ResultSet}. If there are no permissions
	 * given in the ResultSet, <code>null</code> is returned.
	 * 
	 * @param rs the {@link ResultSet} to read from
	 * @return instance of {@link LecturePermissions}, or <code>null</code>
	 * @throws SQLException
	 */
	public static LecturePermissions fromResultSetDefault(ResultSet rs) throws SQLException {
		return fromResultSet(rs, "caneditdefault", "canadmindefault");
	}

	public static void writeForLecture(String lectureId, Map<String, LecturePermissions> permissions)
			throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("DELETE FROM lecturepermission"
					+ " WHERE lectureid = :lectureid");
			stmt.setString("lectureid", lectureId);
			stmt.executeUpdate();
			stmt = connection.prepareStatement("INSERT INTO lecturepermission"
					+ " (lectureid, userid, canedit, canadmin)"
					+ " VALUES (:lectureid, :userid, :canedit, :canadmin)");
			stmt.setString("lectureid", lectureId);
			for (Map.Entry<String, LecturePermissions> entry : permissions.entrySet()) {
				LecturePermissions perm = entry.getValue();
				perm = Sanitizer.handleLecturePermissions(perm);
				stmt.setString("userid", entry.getKey());
				stmt.setBoolean("canedit", perm.edit);
				stmt.setBoolean("canadmin", perm.admin);
				stmt.executeUpdate();
			}
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbLecturePermissions.writeForLecture()", e);
			throw e;
		}
	}

	public static Map<String, LecturePermissions> getForLecture(String lectureId, boolean adminOnly)
			throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("SELECT userid, canedit, canadmin"
					+ " FROM lecturepermission WHERE lectureid = :lectureid");
			stmt.setString("lectureid", lectureId);
			ResultSet rs = stmt.executeQuery();
			Map<String, LecturePermissions> list = new HashMap<>();
			while (rs.next()) {
				boolean admin = rs.getBoolean("canadmin");
				if (adminOnly && !admin)
					continue;
				LecturePermissions perm = new LecturePermissions(rs.getBoolean("canedit"), admin);
				list.put(rs.getString("userid"), perm);
			}
			return list;
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImagePermissions.getForImageBase()", e);
			throw e;
		}
	}

}
