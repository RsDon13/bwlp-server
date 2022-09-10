package org.openslx.bwlp.sat.database.mappers;

import com.google.gson.JsonObject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.bwlp.sat.RuntimeConfig;
import org.openslx.bwlp.sat.database.Database;
import org.openslx.bwlp.sat.database.MysqlConnection;
import org.openslx.bwlp.sat.database.MysqlStatement;
import org.openslx.bwlp.sat.database.Paginator;
import org.openslx.bwlp.sat.database.models.ImageVersionMeta;
import org.openslx.bwlp.sat.database.models.LocalImageVersion;
import org.openslx.bwlp.sat.mail.MailGenerator;
import org.openslx.bwlp.sat.permissions.User;
import org.openslx.bwlp.sat.util.FileSystem;
import org.openslx.bwlp.thrift.iface.*;
import org.openslx.filetransfer.util.ChunkList;
import org.openslx.filetransfer.util.FileChunk;
import org.openslx.util.QuickTimer;
import org.openslx.util.QuickTimer.Task;
import org.openslx.util.Util;
import org.openslx.virtualization.configuration.container.ContainerDefinition;
import org.openslx.virtualization.configuration.container.ContainerMeta;

import java.io.File;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class DbImage {

	private static final Logger LOGGER = LogManager.getLogger(DbImage.class);

	/**
	 * Get list of all images visible to the given user, optionally filtered by
	 * the given list of tags.
	 *
	 * @param user Instance of {@link UserInfo} representing the user in
	 *            question
	 * @param tagSearch list of tags an image must have to be included in the
	 *            list.
	 * @param page page to return
	 * @return {@link List} of {@link ImageSummaryRead}
	 * @throws SQLException
	 */
	public static List<ImageSummaryRead> getAllVisible(UserInfo user, List<String> tagSearch, int page)
			throws SQLException {
		// TODO: Implement tag search functionality
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("SELECT"
					+ " i.imagebaseid, i.latestversionid, i.displayname, i.description,"
					+ " i.osid, i.virtid, i.createtime, i.updatetime, i.ownerid,"
					+ " i.sharemode, i.istemplate, i.canlinkdefault, i.candownloaddefault,"
					+ " i.caneditdefault, i.canadmindefault,"
					+ " lat.expiretime, lat.filesize, lat.isrestricted, lat.isvalid,"
					+ " lat.uploaderid, lat.isprocessed, lat.createtime AS uploadtime,"
					+ " perm.canlink, perm.candownload, perm.canedit, perm.canadmin,"
					+ " Sum(allv.filesize) AS filesizesum, Count(allv.imageversionid) AS versioncount"
					+ " FROM imagebase i"
					+ " LEFT JOIN imageversion lat ON (lat.imageversionid = i.latestversionid)"
					+ " LEFT JOIN imageversion allv ON (allv.imagebaseid = i.imagebaseid)"
					+ " LEFT JOIN imagepermission perm ON (i.imagebaseid = perm.imagebaseid AND perm.userid = :userid)"
					+ " GROUP BY imagebaseid"
					+ Paginator.limitStatement(page));
			stmt.setString("userid", user.userId);
			ResultSet rs = stmt.executeQuery();
			List<ImageSummaryRead> list = new ArrayList<>(100);
			while (rs.next()) {
				list.add(resultSetToSummary(user, rs));
			}
			return list;
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.getAllVisible()", e);
			throw e;
		}
	}

	public static ImageDetailsRead getImageDetails(UserInfo user, String imageBaseId)
			throws TNotFoundException, SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = null;
			// Students should only be able to request a download of an image. Therefore not all information is needed for this task.
			if (user != null && user.role == Role.STUDENT) {
				stmt = connection.prepareStatement("SELECT i.imagebaseid, i.latestversionid, i.virtid, i.osid"
						+ " FROM imagebase i"
						+ " LEFT JOIN imagepermission perm ON (i.imagebaseid = perm.imagebaseid AND perm.userid = :userid)"
						+ " WHERE i.imagebaseid = :imagebaseid");
			} else {
				stmt = connection.prepareStatement("SELECT i.imagebaseid, i.latestversionid,"
						+ " i.displayname, i.description, i.osid, i.virtid, i.createtime, i.updatetime, i.ownerid, i.updaterid,"
						+ " i.sharemode, i.istemplate,"
						+ " i.canlinkdefault, i.candownloaddefault, i.caneditdefault, i.canadmindefault,"
						+ " perm.canlink, perm.candownload, perm.canedit, perm.canadmin"
						+ " FROM imagebase i"
						+ " LEFT JOIN imagepermission perm ON (i.imagebaseid = perm.imagebaseid AND perm.userid = :userid)"
						+ " WHERE i.imagebaseid = :imagebaseid");
			}
			stmt.setString("userid", user == null ? "-" : user.userId);
			stmt.setString("imagebaseid", imageBaseId);
			ResultSet rs = stmt.executeQuery();
			if (!rs.next())
				throw new TNotFoundException();
			// Exists:
			List<String> tags = DbSoftwareTag.getImageTags(connection, imageBaseId);
			List<ImageVersionDetails> versions = getImageVersions(connection, imageBaseId, user);

			ImageDetailsRead image;
			if (user != null && user.role == Role.STUDENT) {
				// Students should only have download permissions.
				// todo evaluate if this is needed and if there is a nicer way to create ImageDetailsRead object
				ImagePermissions defaultPermissions = new ImagePermissions(false, true, false, false);
				image = new ImageDetailsRead(rs.getString("imagebaseid"), rs.getString("latestversionid"),
						versions, "DownloadedImage", null, tags, 0, rs.getString("virtid"), 0, 0, null, null,
						null, false, defaultPermissions);
				image.setUserPermissions(defaultPermissions);
			} else {
				ImagePermissions defaultPermissions = DbImagePermissions.fromResultSetDefault(rs);
				image = new ImageDetailsRead(rs.getString("imagebaseid"), rs.getString("latestversionid"),
						versions, rs.getString("displayname"), rs.getString("description"), tags,
						rs.getInt("osid"), rs.getString("virtid"), rs.getLong("createtime"),
						rs.getLong("updatetime"), rs.getString("ownerid"), rs.getString("updaterid"),
						toShareMode(rs.getString("sharemode")), rs.getByte("istemplate") != 0,
						defaultPermissions);
				image.setUserPermissions(DbImagePermissions.fromResultSetUser(rs));
			}
			User.setCombinedUserPermissions(image, user);
			return image;
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.getImageDetails()", e);
			throw e;
		}
	}

	private final static String localImageBaseSql = "SELECT v.imageversionid, v.imagebaseid,"
			+ " v.filepath, v.filesize, v.uploaderid, v.createtime, v.expiretime, v.isvalid, v.deletestate"
			+ " FROM imageversion v";

	private static LocalImageVersion toLocalImageVersion(ResultSet rs) throws SQLException {
		return new LocalImageVersion(rs.getString("imageversionid"), rs.getString("imagebaseid"),
				rs.getString("filepath"), rs.getLong("filesize"), rs.getString("uploaderid"),
				rs.getLong("createtime"), rs.getLong("expiretime"), rs.getBoolean("isvalid"),
				rs.getString("deletestate"));
	}

	public static LocalImageVersion getLocalImageData(String imageVersionId) throws TNotFoundException,
			SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement(localImageBaseSql
					+ " WHERE imageversionid = :imageversionid");
			stmt.setString("imageversionid", imageVersionId);
			ResultSet rs = stmt.executeQuery();
			if (!rs.next())
				throw new TNotFoundException();
			return toLocalImageVersion(rs);
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.getLocalImageData()", e);
			throw e;
		}
	}

	protected static List<LocalImageVersion> getLocalImageVersions(MysqlConnection connection,
			String imageBaseId) throws SQLException {
		MysqlStatement stmt = connection.prepareStatement(localImageBaseSql
				+ " WHERE imagebaseid = :imagebaseid");
		stmt.setString("imagebaseid", imageBaseId);
		ResultSet rs = stmt.executeQuery();
		List<LocalImageVersion> list = new ArrayList<>();
		while (rs.next()) {
			list.add(toLocalImageVersion(rs));
		}
		return list;
	}

	public static List<LocalImageVersion> getExpiringLocalImageVersions(int maxRemainingDays)
			throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement(localImageBaseSql
					+ " WHERE expiretime < :deadline");
			stmt.setLong("deadline", Util.unixTime() + (maxRemainingDays * 86400));
			ResultSet rs = stmt.executeQuery();
			List<LocalImageVersion> list = new ArrayList<>();
			while (rs.next()) {
				list.add(toLocalImageVersion(rs));
			}
			return list;
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.getAllLocalImages()", e);
			throw e;
		}
	}

	/**
	 * Private helper to create an {@link ImageSummaryRead} instance from a
	 * {@link ResultSet}
	 *
	 * @param rs
	 * @return
	 * @throws SQLException
	 */
	private static ImageSummaryRead resultSetToSummary(UserInfo user, ResultSet rs) throws SQLException {
		ImagePermissions defaultPermissions = DbImagePermissions.fromResultSetDefault(rs);
		ImageSummaryRead entry = new ImageSummaryRead(rs.getString("imagebaseid"),
				rs.getString("latestversionid"), rs.getString("displayname"), rs.getString("description"),
				rs.getInt("osid"), rs.getString("virtid"), rs.getLong("createtime"), rs.getLong("updatetime"),
				rs.getLong("uploadtime"), rs.getLong("expiretime"), rs.getString("ownerid"),
				rs.getString("uploaderid"), toShareMode(rs.getString("sharemode")), rs.getLong("filesize"),
				rs.getByte("isrestricted") != 0, rs.getByte("isvalid") != 0, rs.getByte("isprocessed") != 0,
				rs.getByte("istemplate") != 0, defaultPermissions);
		entry.userPermissions = DbImagePermissions.fromResultSetUser(rs);
		try {
			entry.setFileSizeSum(rs.getLong("filesizesum"));
			entry.setVersionCount(rs.getInt("versioncount"));
		} catch (SQLException e) {
			// Ignore, not set
		}
		User.setCombinedUserPermissions(entry, user);
		return entry;
	}

	/**
	 * Get summary about an image by its base id.
	 *
	 * @param user
	 * @param imageBaseId
	 * @return
	 * @throws SQLException
	 * @throws TNotFoundException
	 */
	public static ImageSummaryRead getImageSummary(UserInfo user, String imageBaseId) throws SQLException,
			TNotFoundException {
		try (MysqlConnection connection = Database.getConnection()) {
			return getImageSummary(connection, user, imageBaseId);
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.getImageSummary()", e);
			throw e;
		}
	}

	protected static ImageSummaryRead getImageSummary(MysqlConnection connection, UserInfo user,
			String imageBaseId) throws SQLException, TNotFoundException {
		MysqlStatement stmt = connection.prepareStatement("SELECT"
				+ " i.imagebaseid, i.latestversionid, i.displayname, i.description,"
				+ " i.osid, i.virtid, i.createtime, i.updatetime, i.ownerid,"
				+ " i.sharemode, i.istemplate, i.canlinkdefault, i.candownloaddefault,"
				+ " i.caneditdefault, i.canadmindefault,"
				+ " lat.expiretime, lat.filesize, lat.isrestricted, lat.isvalid,"
				+ " lat.uploaderid, lat.isprocessed, lat.createtime AS uploadtime,"
				+ " perm.canlink, perm.candownload, perm.canedit, perm.canadmin"
				+ " FROM imagebase i"
				+ " LEFT JOIN imageversion lat ON (lat.imageversionid = i.latestversionid)"
				+ " LEFT JOIN imagepermission perm ON (i.imagebaseid = perm.imagebaseid AND perm.userid = :userid)"
				+ " WHERE i.imagebaseid = :imagebaseid");
		stmt.setString("userid", user.userId);
		stmt.setString("imagebaseid", imageBaseId);
		ResultSet rs = stmt.executeQuery();
		if (!rs.next()) {
			throw new TNotFoundException();
		}
		return resultSetToSummary(user, rs);
	}

	protected static List<ImageVersionDetails> getImageVersions(MysqlConnection connection, String imageBaseId, UserInfo user)
			throws SQLException {
		List<ImageVersionDetails> versionList = new ArrayList<>();
		MysqlStatement stmt = null;
		if (user != null && user.role == Role.STUDENT) {
			stmt = connection.prepareStatement("SELECT"
					+ " imageversionid, createtime, expiretime, filesize,"
					+ " isrestricted, isvalid, isprocessed"
					+ " FROM imageversion"
					+ " WHERE imagebaseid = :imagebaseid AND isrestricted = 0");
		} else {
			stmt = connection.prepareStatement("SELECT"
							+ " imageversionid, createtime, expiretime, filesize, uploaderid,"
							+ " isrestricted, isvalid, isprocessed"
							+ " FROM imageversion"
							+ " WHERE imagebaseid = :imagebaseid");
		}
		stmt.setString("imagebaseid", imageBaseId);
		ResultSet rs = stmt.executeQuery();

		while (rs.next()) {
			String imageVersionId = rs.getString("imageversionid");
			String uploaderID = "";
			// Only student doesn't know the uploaderid
			if (user == null || user.role != Role.STUDENT) {
				uploaderID = rs.getString("uploaderid");
			}
			versionList.add(new ImageVersionDetails(imageVersionId, rs.getLong("createtime"),
					rs.getLong("expiretime"), rs.getLong("filesize"), uploaderID,
					rs.getByte("isrestricted") != 0, rs.getByte("isvalid") != 0,
					rs.getByte("isprocessed") != 0,
					DbSoftwareTag.getImageVersionSoftwareList(connection, imageVersionId)));
		}
		stmt.close();
		return versionList;
	}

	/**
	 * Create new row in the imagebase table.
	 *
	 * @param user the user the image will belong to
	 * @param imageName name of the image to be created
	 * @return UUID of the newly created image
	 */
	public static String createImage(UserInfo user, String imageName) throws SQLException {
		if (imageName.length() > 100) {
			imageName = imageName.substring(0, 100);
		}
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("INSERT INTO imagebase"
					+ " (imagebaseid, displayname, createtime, updatetime, ownerid, updaterid, sharemode,"
					+ " istemplate, canlinkdefault, candownloaddefault, caneditdefault, canadmindefault)"
					+ " VALUES"
					+ " (:baseid, :name, UNIX_TIMESTAMP(), UNIX_TIMESTAMP(), :userid, :userid, 'LOCAL',"
					+ " 0, 0, 0, 0, 0)");
			String imageUuid = UUID.randomUUID().toString();
			stmt.setString("baseid", imageUuid);
			stmt.setString("name", imageName);
			stmt.setString("userid", user.userId);
			stmt.executeUpdate();
			connection.commit();
			return imageUuid;
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.createImage()", e);
			throw e;
		}
	}

	/**
	 * Create or update a base image with the given publish data.
	 * Used for replication from master server.
	 *
	 * @param user The user who triggered the download, and will be considered
	 *            the creator; if null, the creator of the image will be used
	 * @param image The image to create
	 * @throws SQLException
	 */
	public static void writeBaseImage(ImagePublishData image) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("INSERT INTO imagebase"
					+ " (imagebaseid, displayname, description, osid, virtid, createtime,"
					+ "  updatetime, ownerid, updaterid, sharemode, istemplate,"
					+ "  canlinkdefault, candownloaddefault, caneditdefault, canadmindefault)"
					+ "               VALUES                          "
					+ " (:imagebaseid, :displayname, :description, :osid, :virtid, :unixtime,"
					+ "  :unixtime, :userid, :userid, :sharemode, :istemplate,"
					+ "   1, 1, 0, 0)                                 "
					+ " ON DUPLICATE KEY UPDATE                       "
					+ "  displayname = VALUES(displayname), description = VALUES(description),"
					+ "  osid = VALUES(osid), virtid = VALUES(virtid), updatetime = VALUES(updatetime),"
					+ "  updaterid = VALUES(updaterid), istemplate = VALUES(istemplate)");
			stmt.setString("imagebaseid", image.imageBaseId);
			stmt.setString("displayname", image.imageName);
			stmt.setString("description", image.description);
			stmt.setInt("osid", image.osId);
			stmt.setString("virtid", image.virtId);
			stmt.setLong("unixtime", Util.unixTime());
			stmt.setString("userid", image.owner.userId);
			stmt.setString("sharemode", "DOWNLOAD");
			stmt.setBoolean("istemplate", image.isTemplate);
			stmt.executeUpdate();
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.writeBaseImage()", e);
			throw e;
		}
	}

	public static void updateImageMetadata(UserInfo user, String imageBaseId, ImageBaseWrite image)
			throws SQLException {
		if (image.imageName.length() > 100) {
			image.imageName = image.imageName.substring(0, 100);
		}
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("UPDATE imagebase"
					+ " SET displayname = :imagename, description = :description,"
					+ " osid = :osid, virtid = :virtid,"
					+ (user == null || User.isSuperUser(user) ? " istemplate = :istemplate," : "")
					+ " canlinkdefault = :canlink,"
					+ " candownloaddefault = :candownload, caneditdefault = :canedit,"
					+ (user != null ? " updaterid = :updaterid, updatetime = UNIX_TIMESTAMP()," : "")
					+ " canadmindefault = :canadmin"
					+ " WHERE imagebaseid = :baseid");
			stmt.setString("baseid", imageBaseId);
			stmt.setString("imagename", image.imageName);
			stmt.setString("description", image.description);
			stmt.setInt("osid", image.osId);
			stmt.setString("virtid", image.virtId);
			try {
				stmt.setBoolean("istemplate", image.isTemplate);
			} catch (IllegalArgumentException e) {
				// This might not exist in the query, so swallow the exception
			}
			stmt.setBoolean("canlink", image.defaultPermissions.link);
			stmt.setBoolean("candownload", image.defaultPermissions.download);
			stmt.setBoolean("canedit", image.defaultPermissions.edit);
			stmt.setBoolean("canadmin", image.defaultPermissions.admin);
			if (user != null) {
				stmt.setString("updaterid", user.userId);
			}
			stmt.executeUpdate();
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.updateImageMetadata()", e);
			throw e;
		}
	}

	public static void setImageOwner(String imageBaseId, String newOwnerId, UserInfo changingUser)
			throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt;
			if (changingUser == null) {
				stmt = connection.prepareStatement("UPDATE imagebase"
						+ " SET ownerid = :ownerid WHERE imagebaseid = :baseid");
				stmt.setString("ownerid", newOwnerId);
				stmt.setString("baseid", imageBaseId);
			} else {
				stmt = connection.prepareStatement("UPDATE imagebase"
						+ " SET ownerid = :ownerid, updaterid = :updaterid, updatetime = UNIX_TIMESTAMP()"
						+ " WHERE imagebaseid = :baseid");
				stmt.setString("ownerid", newOwnerId);
				stmt.setString("updaterid", changingUser.userId);
				stmt.setString("baseid", imageBaseId);
			}
			stmt.executeUpdate();
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.setImageOwner()", e);
			throw e;
		}
	}

	/**
	 * Get the UUID of the image base belonging to the given image version UUID.
	 * Returns <code>null</code> if the UUID does not exist.
	 *
	 * @param imageVersionId
	 * @return
	 * @throws SQLException
	 * @throws TNotFoundException
	 */
	public static String getBaseIdForVersionId(String imageVersionId) throws SQLException, TNotFoundException {
		try (MysqlConnection connection = Database.getConnection()) {
			return getBaseIdForVersionId(connection, imageVersionId);
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.getBaseIdForVersionId()", e);
			throw e;
		}
	}

	/**
	 * Get the UUID of the image base belonging to the given image version UUID.
	 *
	 * @param imageVersionId
	 * @return
	 * @throws SQLException
	 * @throws TNotFoundException version id is unknown
	 */
	protected static String getBaseIdForVersionId(MysqlConnection connection, String imageVersionId)
			throws SQLException, TNotFoundException {
		MysqlStatement stmt = connection.prepareStatement("SELECT imagebaseid FROM imageversion"
				+ " WHERE imageversionid = :imageversionid LIMIT 1");
		stmt.setString("imageversionid", imageVersionId);
		ResultSet rs = stmt.executeQuery();
		if (!rs.next())
			throw new TNotFoundException();
		return rs.getString("imagebaseid");
	}

	private static ShareMode toShareMode(String string) {
		return ShareMode.valueOf(string);
	}

	/**
	 * Update meta data of a specific image version.
	 *
	 * @param user user doing the edit
	 * @param imageVersionId UUID of image version
	 * @param image meta data to set
	 * @throws SQLException
	 * @throws TNotFoundException
	 */
	public static void updateImageVersion(UserInfo user, String imageVersionId, ImageVersionWrite image)
			throws SQLException, TNotFoundException {
		try (MysqlConnection connection = Database.getConnection()) {
			String baseId = getBaseIdForVersionId(connection, imageVersionId);
			if (baseId == null)
				throw new TNotFoundException();
			// First update version table
			MysqlStatement stmtVersion = connection.prepareStatement("UPDATE imageversion v SET"
					+ " v.isrestricted = :isrestricted"
					+ " WHERE v.imageversionid = :versionid");
			stmtVersion.setString("versionid", imageVersionId);
			stmtVersion.setBoolean("isrestricted", image.isRestricted);
			if (stmtVersion.executeUpdate() != 0) {
				// Then base table
				MysqlStatement stmtBase = connection.prepareStatement("UPDATE imagebase b SET"
						+ " b.updaterid = :userid, b.updatetime = UNIX_TIMESTAMP()"
						+ " WHERE b.imagebaseid = :baseid");
				stmtBase.setString("userid", user.userId);
				stmtBase.setString("baseid", baseId);
				stmtBase.executeUpdate();
			}
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.updateImageVersion()", e);
			throw e;
		}
	}

	/**
	 * Mark given image for deletion. The image is marked for deletion by
	 * setting the expire timestamp to the past, and by setting the
	 * image disabled and invalid. Next time the cleanup task runs, the image
	 * will be deleted.
	 *
	 * @param imageVersionId UUID of image version to delete
	 * @throws SQLException
	 * @throws TNotFoundException
	 */
	public static void markForDeletion(String... imageVersionIds) throws SQLException, TNotFoundException {
		if (imageVersionIds == null || imageVersionIds.length == 0)
			return;
		List<String> affectedList;
		try (MysqlConnection connection = Database.getConnection()) {
			{
				// Disable version in question
				MysqlStatement checkStmt = connection.prepareStatement("SELECT imageversionid"
						+ " FROM imageversion"
						+ " WHERE imageversionid = :versionid AND"
						+ " (expiretime > UNIX_TIMESTAMP() OR isvalid <> 0)");
				MysqlStatement disableStmt = connection.prepareStatement("UPDATE imageversion"
						+ " SET expiretime = 1234567890, isvalid = 0"
						+ " WHERE imageversionid = :versionid");
				affectedList = new ArrayList<>(imageVersionIds.length);
				for (String imageVersionId : imageVersionIds) {
					if (imageVersionId == null)
						continue;
					// Query state explicitly instead of relying on affected rows, as it's
					// broken depending on java version, mysql version and other things
					checkStmt.setString("versionid", imageVersionId);
					ResultSet cr = checkStmt.executeQuery();
					if (!cr.next())
						continue;
					// Was not disabled already, do so
					disableStmt.setString("versionid", imageVersionId);
					disableStmt.executeUpdate();
					affectedList.add(imageVersionId);
				}
				// Commit what we did so far
				checkStmt.close();
				disableStmt.close();
			}
			connection.commit();
			if (!affectedList.isEmpty()) {
				updateLatestVersion(connection, affectedList.toArray(new String[affectedList.size()]));
			}
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.markForDeletion()", e);
			throw e;
		}
	}

	public static void setShareMode(String imageBaseId, ImageBaseWrite newData) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("UPDATE imagebase"
					+ " SET sharemode = :sharemode WHERE imagebaseid = :baseid LIMIT 1");
			stmt.setString("baseid", imageBaseId);
			stmt.setString("sharemode", newData.shareMode.name());
			stmt.executeUpdate();
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.setShareMode()", e);
			throw e;
		}
	}

	public static void createImageVersion(String imageBaseId, String imageVersionId, UserInfo owner,
			long fileSize, String filePath, ImageVersionWrite versionSettings, ChunkList chunks,
			byte[] machineDescription) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			final long nowSecs = Util.unixTime();
			final long expireTime = nowSecs + RuntimeConfig.getMaxImageValiditySeconds();
			MysqlStatement verStmt = connection.prepareStatement("INSERT INTO imageversion"
					+ " (imageversionid, imagebaseid, createtime, expiretime, filesize, filepath, uploaderid,"
					+ "  isrestricted, isvalid, isprocessed, mastersha1, virtualizerconfig)"
					+ "         VALUES                     "
					+ " (:imageversionid, :imagebaseid, :createtime, :expiretime, :filesize, :filepath,"
					+ "  :uploaderid, :isrestricted, :isvalid, :isprocessed, :mastersha1, :virtualizerconfig)");
			verStmt.setString("imageversionid", imageVersionId);
			verStmt.setString("imagebaseid", imageBaseId);
			verStmt.setLong("createtime", nowSecs);
			verStmt.setLong("expiretime", expireTime);
			verStmt.setLong("filesize", fileSize);
			verStmt.setString("filepath", filePath);
			verStmt.setString("uploaderid", owner.userId);
			verStmt.setBoolean("isrestricted", versionSettings == null ? false : versionSettings.isRestricted);
			verStmt.setBoolean("isvalid", true);
			verStmt.setBoolean("isprocessed", false);
			verStmt.setBinary("mastersha1", null); // TODO
			verStmt.setBinary("virtualizerconfig", machineDescription);
			verStmt.executeUpdate();
			writeChunks(connection, imageVersionId, chunks);
			LocalImageVersion liv = new LocalImageVersion(imageVersionId, imageBaseId, filePath, fileSize,
					owner.userId, nowSecs, expireTime, true, DeleteState.KEEP.name());
			DbLecture.autoUpdateUsedImage(connection, imageBaseId, liv);
			// Update edit timestamp and edit user
			MysqlStatement baseStmt = connection.prepareStatement("UPDATE imagebase SET"
					+ " updatetime = :updatetime, updaterid = :updaterid"
					+ " WHERE imagebaseid = :imagebaseid LIMIT 1");
			baseStmt.setString("imagebaseid", imageBaseId);
			baseStmt.setString("updaterid", owner.userId);
			baseStmt.setLong("updatetime", nowSecs);
			baseStmt.executeUpdate();
			// Make this version the latest version
			setLatestVersion(connection, imageBaseId, liv);
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.createImageVersion()", e);
			throw e;
		}
	}

	private static void writeChunks(MysqlConnection connection, String imageVersionId, ChunkList chunks)
			throws SQLException {
		if (chunks == null || chunks.isEmpty())
			return;
		for (FileChunk chunk : chunks.getAll()) {
			if (chunk.getSha1Sum() == null)
				return;
		}
		MysqlStatement stmt = connection.prepareStatement("INSERT IGNORE INTO imageblock"
				+ " (imageversionid, startbyte, blocksize, blocksha1, ismissing) VALUES"
				+ " (:imageversionid, :startbyte, :blocksize, :blocksha1, 0)");
		stmt.setString("imageversionid", imageVersionId);
		for (FileChunk chunk : chunks.getAll()) {
			stmt.setLong("startbyte", chunk.range.startOffset);
			stmt.setInt("blocksize", chunk.range.getLength());
			stmt.setBinary("blocksha1", chunk.getSha1Sum());
			stmt.executeUpdate();
		}
	}

	/**
	 * Set validity of given image versions. Returns list of images where the
	 * validity actually changed.
	 *
	 * @param connection
	 * @param valid
	 * @param imageVersion
	 * @return
	 * @throws SQLException
	 */
	protected static LocalImageVersion[] markValid(MysqlConnection connection, boolean valid,
			LocalImageVersion... imageVersion) throws SQLException {
		if (imageVersion == null || imageVersion.length == 0)
			return new LocalImageVersion[0];
		MysqlStatement stmt = connection.prepareStatement("UPDATE imageversion SET isvalid = :valid"
				+ " WHERE imageversionid = :imageversionid");
		stmt.setBoolean("valid", valid);
		List<LocalImageVersion> retList = new ArrayList<>(imageVersion.length);
		for (LocalImageVersion version : imageVersion) {
			stmt.setString("imageversionid", version.imageVersionId);
			if (stmt.executeUpdate() != 0) {
				retList.add(version);
			}
		}
		return retList.toArray(new LocalImageVersion[retList.size()]);
	}

	public static void markValid(boolean valid, boolean async, LocalImageVersion... imageVersions)
			throws SQLException {
		if (imageVersions == null || imageVersions.length == 0)
			return;
		LocalImageVersion[] affectedVersions;
		try (MysqlConnection connection = Database.getConnection()) {
			affectedVersions = markValid(connection, valid, imageVersions);
			if (!async) {
				updateLatestVersion(connection, affectedVersions);
			}
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.markInvalid()", e);
			throw e;
		}
		if (async) {
			updateLatestVersionAsync(affectedVersions);
		}
	}

	public static void deleteVersionPermanently(LocalImageVersion image) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			// Unlink any linked lectures
			DbLecture.unlinkFromImageVersion(connection, image.imageVersionId);
			//DbLecture.deletePermanently(connection, image);
			// Unlink latest version field from image base
			MysqlStatement unlinkStmt = connection.prepareStatement("UPDATE imagebase SET latestversionid = NULL"
					+ " WHERE latestversionid = :imageversionid");
			unlinkStmt.setString("imageversionid", image.imageVersionId);
			unlinkStmt.executeUpdate();
			MysqlStatement stmt = connection.prepareStatement("DELETE FROM imageversion"
					+ " WHERE imageversionid = :imageversionid");
			stmt.setString("imageversionid", image.imageVersionId);
			stmt.executeUpdate();
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.deleteVersionPermanently(2)", e);
			throw e;
		}
	}

	private static void updateLatestVersionAsync(final LocalImageVersion... changingVersion) {
		if (changingVersion == null || changingVersion.length == 0)
			return;
		QuickTimer.scheduleOnce(new Task() {
			@Override
			public void fire() {
				try (MysqlConnection connection = Database.getConnection()) {
					updateLatestVersion(connection, changingVersion);
					connection.commit();
				} catch (SQLException e) {
					LOGGER.error("Query failed in DbImage.updateLatestVersionAsync()", e);
				}
			}
		});
	}

	private static void updateLatestVersion(MysqlConnection connection, LocalImageVersion... versions)
			throws SQLException {
		if (versions == null || versions.length == 0)
			return;
		for (LocalImageVersion version : versions) {
			try {
				versionValidityChanged(connection, version.imageVersionId, version.imageBaseId);
			} catch (TNotFoundException e) {
				// Swallow - logging happens in called method
			}
		}
	}

	private static void updateLatestVersion(MysqlConnection connection, String... versions)
			throws SQLException {
		if (versions == null || versions.length == 0)
			return;
		for (String version : versions) {
			try {
				versionValidityChanged(connection, version, null);
			} catch (TNotFoundException e) {
				// Swallow - logging happens in called method
			}
		}
	}

	/**
	 * Makes sure the latestVersionId-field of the given base image is
	 * consistent, while also updating any affected lectures.
	 *
	 * @param connection mysql connection to use
	 * @param changingImageVersionId the version id of the image that changed
	 *            (REQUIRED)
	 * @param imageBaseId the base id of the image that changed (OPTIONAL, will
	 *            be determined if missing)
	 * @throws TNotFoundException
	 * @throws SQLException
	 */
	private static void versionValidityChanged(MysqlConnection connection, String changingImageVersionId,
			String imageBaseId) throws TNotFoundException, SQLException {
		if (imageBaseId == null) {
			imageBaseId = DbImage.getBaseIdForVersionId(connection, changingImageVersionId);
			if (imageBaseId == null) {
				LOGGER.warn("versionValidityChanged for non-existent version " + changingImageVersionId);
				throw new TNotFoundException();
			}
		}
		// Determine new latest version, as we might have to update the imagebase and lecture tables
		List<LocalImageVersion> versions = DbImage.getLocalImageVersions(connection, imageBaseId);
		LocalImageVersion latestVersion = null;
		LocalImageVersion changingVersion = null;
		for (LocalImageVersion version : versions) {
			if (version.imageVersionId.equals(changingImageVersionId)) {
				changingVersion = version;
			}
			if (version.deleteState == DeleteState.KEEP && version.isValid
					&& (latestVersion == null || version.createTime > latestVersion.createTime)) {
				File versionFile = FileSystem.composeAbsoluteImagePath(version);
				if (versionFile != null) {
					if (versionFile.canRead() && versionFile.length() == version.fileSize) {
						latestVersion = version;
					} else {
						markValid(connection, false, version);
					}
				}
			}
		}
		if (changingVersion == null) {
			LOGGER.warn("BUG: oldVersion ninjad away on updateLatestVersion (" + changingImageVersionId + ")");
		} else {
			// Switch any lectures linking to this version if applicable
			if (changingVersion.isValid) {
				// The version that changed became valid. In case it was the latest version (by date), this is now
				// the version to be used by auto-updating lectures. If it wasn't the latest version, the following
				// call will do nothing
				DbLecture.autoUpdateUsedImage(connection, imageBaseId, latestVersion);
			} else {
				// The version that changed is now invalid. Switch any lecture using it to the latest
				// available version, ignoring the "auto update" flag of the lecture
				DbLecture.forcefullySwitchUsedImage(connection, changingVersion, latestVersion);
			}
		}
		// Now update the latestversionid of the baseimage if applicable
		if (setLatestVersion(connection, imageBaseId, latestVersion)) {
			MailGenerator.sendImageVersionDeleted(imageBaseId, changingVersion, latestVersion);
		}
	}

	/**
	 * Set the latest version id of the given base image. Returns true if and
	 * only if the latest version id of the base image did actually change
	 * through this call.
	 *
	 * @param connection mysql connection to use
	 * @param imageBaseId base id of image in question
	 * @param newLatest image version that is to become the latest version, or
	 *            <code>null</code> if there is no valid version
	 * @return true if changed to a different, non-null image
	 * @throws SQLException
	 */
	private static boolean setLatestVersion(MysqlConnection connection, String imageBaseId,
			LocalImageVersion newLatest) throws SQLException {
		// Determine manually if anything changed, as executeQuery() always returns 1 for some reason
		boolean latestVersionChanged = true;
		do {
			MysqlStatement ds = connection.prepareStatement(
					"SELECT latestversionid FROM imagebase WHERE imagebaseid = :imagebaseid");
			ds.setString("imagebaseid", imageBaseId);
			ResultSet drs = ds.executeQuery();
			if (drs.next()) {
				String currentLatest = drs.getString("latestversionid");
				if (currentLatest == null && (newLatest == null || newLatest.imageVersionId == null)) {
					latestVersionChanged = false;
				} else if (currentLatest != null && newLatest != null
						&& currentLatest.equals(newLatest.imageVersionId)) {
					latestVersionChanged = false;
				}
			}
		} while (false);
		// Update latestversionid reference in imagebase table
		MysqlStatement latestStmt = connection.prepareStatement("UPDATE imagebase SET latestversionid = :newversionid"
				+ " WHERE imagebaseid = :imagebaseid");
		latestStmt.setString("newversionid", newLatest == null ? null : newLatest.imageVersionId);
		latestStmt.setString("imagebaseid", imageBaseId);
		// If nothing changed (because the deleted version was not the latest), bail out
		latestStmt.executeUpdate();
		if (!latestVersionChanged)
			return false;
		// It there is no valid version, bail out as a shortcut - queries below wouldn't do anything
		if (newLatest == null)
			return true;
		// Latest version changed - update expire dates of related versions
		// Set short expire date for versions that are NOT the latest version but are still marked valid
		long shortExpire = Util.unixTime() + RuntimeConfig.getOldVersionExpireSeconds();
		MysqlStatement oldStmt = connection.prepareStatement("UPDATE imageversion SET"
				+ " expiretime = If(expiretime < :shortexpire, expiretime, :shortexpire)"
				+ " WHERE imagebaseid = :imagebaseid AND imageversionid <> :imageversionid AND isvalid = 1");
		oldStmt.setString("imageversionid", newLatest.imageVersionId);
		oldStmt.setString("imagebaseid", imageBaseId);
		oldStmt.setLong("shortexpire", shortExpire);
		oldStmt.executeUpdate();
		// Now set a long expire date for the latest version, as it might have been shortened before
		MysqlStatement newStmt = connection.prepareStatement("UPDATE imageversion SET"
				+ " expiretime = If(createtime + :maxvalid > expiretime, createtime + :maxvalid, expiretime)"
				+ " WHERE imageversionid = :imageversionid");
		newStmt.setString("imageversionid", newLatest.imageVersionId);
		newStmt.setLong("maxvalid", RuntimeConfig.getMaxImageValiditySeconds());
		newStmt.executeUpdate();
		return true;
	}

	/**
	 * Get all images with mussing virtid or osid.
	 *
	 * @return
	 * @throws SQLException
	 */
	public static List<LocalImageVersion> getVersionsWithMissingData() throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement(localImageBaseSql
					+ " INNER JOIN imagebase b USING (imagebaseid)"
					+ " WHERE b.virtid IS NULL OR b.osid IS NULL");
			ResultSet rs = stmt.executeQuery();
			List<LocalImageVersion> list = new ArrayList<>();
			while (rs.next()) {
				// Copy of helper so we can pass 0 for expire date
				list.add(new LocalImageVersion(rs.getString("imageversionid"), rs.getString("imagebaseid"),
						rs.getString("filepath"), rs.getLong("filesize"), rs.getString("uploaderid"),
						rs.getLong("createtime"), 0, rs.getBoolean("isvalid"), rs.getString("deletestate")));
			}
			return list;
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.getVersionsWithMissingData()", e);
			throw e;
		}
	}

	public static int deleteOrphanedBases() throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			// Get all image base entries which have no image version
			MysqlStatement sel = connection.prepareStatement("SELECT i.imagebaseid FROM imagebase i"
					+ " LEFT JOIN imageversion v USING (imagebaseid)"
					+ " WHERE ("
					+ "   i.updatetime < :cutoff1 OR (i.updatetime < :cutoff2 AND (i.updatetime - i.createtime) < 600))"
					+ " AND v.imageversionid IS NULL");
			sel.setLong("cutoff1", Util.unixTime() - 86400 * 14);
			sel.setLong("cutoff2", Util.unixTime() - 3600 * 2);
			ResultSet rs = sel.executeQuery();
			// Now delete them all
			MysqlStatement stmt = connection.prepareStatement("DELETE FROM imagebase"
					+ " WHERE imagebaseid = :imagebaseid");
			int ret = 0;
			while (rs.next()) {
				String baseId = null;
				try {
					baseId = rs.getString("imagebaseid");
					stmt.setString("imagebaseid", baseId);
					ret += stmt.executeUpdate();
				} catch (SQLException e) {
					LOGGER.warn("Could not delete base image " + baseId, e);
				}
			}
			connection.commit();
			return ret;
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.deleteOrphanedBases()", e);
			throw e;
		}
	}

	public static ImageVersionMeta getVersionDetails(String imageVersionId) throws SQLException,
			TNotFoundException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("SELECT"
					+ " imageversionid, imagebaseid, virtualizerconfig FROM imageversion"
					+ " WHERE imageversionid = :imageversionid");
			stmt.setString("imageversionid", imageVersionId);
			ResultSet rs = stmt.executeQuery();
			if (!rs.next())
				throw new TNotFoundException();
			return new ImageVersionMeta(imageVersionId, rs.getString("imagebaseid"),
					rs.getBytes("virtualizerconfig"), DbImageBlock.getBlockHashes(connection, imageVersionId));
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.getVersionDetails()", e);
			throw e;
		}
	}

	public static byte[] getVirtualizerConfig(String imageVersionId) throws SQLException,
			TNotFoundException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("SELECT"
					+ " virtualizerconfig FROM imageversion"
					+ " WHERE imageversionid = :imageversionid");
			stmt.setString("imageversionid", imageVersionId);
			ResultSet rs = stmt.executeQuery();
			if (!rs.next())
				throw new TNotFoundException();
			return rs.getBytes("virtualizerconfig");
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.getVirtualizerConfig()", e);
			throw e;
		}
	}

	public static void setVirtualizerConfig(String imageVersionId, byte[] machineDescription) throws SQLException,
			TNotFoundException {
		if (imageVersionId == null || machineDescription == null || machineDescription.length == 0)
			return;
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("UPDATE imageversion SET virtualizerconfig = :virtualizerconfig"
					+ " WHERE imageversionid = :imageversionid");
			stmt.setString("imageversionid", imageVersionId);
			stmt.setBinary("virtualizerconfig", machineDescription);
			stmt.executeUpdate();
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.setVersionDetails()", e);
			throw e;
		}
	}

	public enum DeleteState {
		KEEP,
		SHOULD_DELETE,
		WANT_DELETE;
	}

	public static void setDeletion(DeleteState shouldDelete, String... imageVersionIds) throws SQLException {
		if (imageVersionIds == null || imageVersionIds.length == 0 || shouldDelete == null)
			return;
		String ignoredOldState;
		if (shouldDelete == DeleteState.SHOULD_DELETE) {
			ignoredOldState = DeleteState.WANT_DELETE.name();
		} else {
			ignoredOldState = "invalid";
		}
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("UPDATE imageversion SET deletestate = :newstate"
					+ " WHERE imageversionid = :imageversionid AND deletestate <> :oldstate");
			stmt.setString("newstate", shouldDelete.name());
			stmt.setString("oldstate", ignoredOldState);
			for (String imageVersionId : imageVersionIds) {
				if (imageVersionId == null)
					continue;
				stmt.setString("imageversionid", imageVersionId);
				stmt.executeUpdate();
			}
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.setDeletion()", e);
			throw e;
		}
	}

	public static List<LocalImageVersion> getLocalWithState(DeleteState state) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement(localImageBaseSql
					+ " WHERE deletestate = :deletestate");
			stmt.setString("deletestate", state.name());
			ResultSet rs = stmt.executeQuery();
			List<LocalImageVersion> list = new ArrayList<>();
			while (rs.next()) {
				list.add(toLocalImageVersion(rs));
			}
			return list;
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.getLocalWithState()", e);
			throw e;
		}
	}

	public static void deleteBasePermanently(String imageBaseId) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("DELETE FROM imagebase"
					+ " WHERE imagebaseid = :imagebaseid");
			stmt.setString("imagebaseid", imageBaseId);
			stmt.executeUpdate();
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.deleteBasePermanently()", e);
			throw e;
		}
	}

	/**
	 * Reset all image versions where the server decided that they should be
	 * deleted to the 'keep' state.
	 *
	 * @return list of version ids that were reset
	 *
	 * @throws SQLException
	 */
	public static Set<String> resetDeleteState() throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			// Get
			MysqlStatement sstmt = connection.prepareStatement("SELECT imageversionid FROM imageversion"
					+ " WHERE deletestate = :should");
			sstmt.setString("should", DeleteState.SHOULD_DELETE.name());
			ResultSet rs = sstmt.executeQuery();
			Set<String> list = new HashSet<>();
			while (rs.next()) {
				list.add(rs.getString("imageversionid"));
			}
			// Update
			MysqlStatement ustmt = connection.prepareStatement("UPDATE imageversion SET deletestate = :keep"
					+ " WHERE deletestate = :should");
			ustmt.setString("keep", DeleteState.KEEP.name());
			ustmt.setString("should", DeleteState.SHOULD_DELETE.name());
			ustmt.executeUpdate();
			connection.commit();
			return list;
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.resetDeleteState()", e);
			throw e;
		}
	}

	public static void setExpireDate(String imageVersionId, long expireTime) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("UPDATE imageversion SET expiretime = :expiretime"
					+ " WHERE imageversionid = :imageversionid");
			stmt.setString("imageversionid", imageVersionId);
			stmt.setLong("expiretime", expireTime);
			stmt.executeUpdate();
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.setExpireDate()", e);
			throw e;
		}
	}

	/**
	 * Get all known file names of images, regardless of whether they are working/valid.
	 */
	public static Set<String> getAllFilenames() throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("SELECT filepath FROM imageversion");
			ResultSet rs = stmt.executeQuery();
			Set<String> result = new HashSet<>();
			while (rs.next()) {
				result.add(rs.getString("filepath"));
			}
			return result;
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.getAllFilenames()", e);
			throw e;
		}
	}


	public static List<ContainerImages> getContainerImageCluster () throws SQLException {

		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement(
					"SELECT ib.imagebaseid, iv.filepath, iv.filesize, iv.virtualizerconfig, u.firstname, u.lastname"
					+ " FROM imagebase AS ib"
					+ " JOIN imageversion AS iv ON (ib.imagebaseid=iv.imagebaseid AND ib.virtid = 'docker' AND ib.latestversionid IS NOT NULL)"
					+ " JOIN user as u ON ib.ownerid = u.userid");
			ResultSet rs = stmt.executeQuery();
			List<ContainerImages> result = new ArrayList<>();
			while (rs.next()) {
				ContainerDefinition condev = ContainerDefinition.fromByteArray(rs.getBytes("iv.virtualizerconfig"));
				if (condev.getContainerMeta().getImageType() == ContainerMeta.ContainerImageType.LECTURE)
					continue;
				ContainerImages entry = new ContainerImages(
						rs.getString("u.firstname"),
						rs.getString("u.lastname"),
						condev.getContainerMeta().getImageRepo(),
						condev.getContainerMeta().getImageType().name()
				);
				result.add(entry);
			}
			return result;
		} catch (Exception e) {
			LOGGER.error("Query failed in DbImage.getContainerImages()", e);
			throw e;
		}
	}

	public static String getContainerImageMetadata(String imagebaseid) throws SQLException {

		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement(
					"SELECT ib.imagebaseid, ib.displayname, iv.filepath, iv.filesize, iv.virtualizerconfig"
							+ " FROM imagebase AS ib"
							+ " JOIN imageversion AS iv ON (ib.latestversionid=iv.imageversionid AND ib.virtid = 'docker' AND ib.latestversionid IS NOT NULL)"
							+ " WHERE ib.imagebaseid = :imagebaseid");
			stmt.setString("imagebaseid", imagebaseid);
			ResultSet rs = stmt.executeQuery();

			JsonObject resultJson = new JsonObject();
			while (rs.next()) {
				ContainerDefinition condev = ContainerDefinition.fromByteArray(
						rs.getBytes("iv.virtualizerconfig"));
				// currently only data images are returned
				if (condev.getContainerMeta().getImageType() != ContainerMeta.ContainerImageType.DATA)
					break;

				resultJson.addProperty("displayname", rs.getString("ib.displayname"));
				resultJson.addProperty("imagepath", rs.getString("iv.filepath"));
				resultJson.addProperty("filesize", rs.getString("iv.filesize"));

				resultJson.addProperty("image_recipe", condev.getContainerRecipe());
				resultJson.addProperty("image_repo", condev.getContainerMeta().getImageRepo());
				resultJson.addProperty("build_context_method",
						condev.getContainerMeta().getContainerImageContext());
				resultJson.addProperty("build_context_url", condev.getContainerMeta().getBuildContextUrl());
				break;
			}

			return resultJson.toString();
		} catch (Exception e) {
			LOGGER.error("Query failed in DbImage.getContainerImages()", e);
			throw e;
		}
	}

	static class ContainerImages {
		public final String owner_firstname;
		public final String owner_lastname;
		public final String image;
		public final String image_type;

		public ContainerImages(String owner_firstname, String owner_lastname, String image, String image_type) {
			this.owner_firstname = owner_firstname;
			this.owner_lastname = owner_lastname;
			this.image = image;
			this.image_type = image_type;
		}
	}


}
