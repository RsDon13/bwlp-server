package org.openslx.bwlp.sat.database.mappers;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.bwlp.sat.database.Database;
import org.openslx.bwlp.sat.database.MysqlConnection;
import org.openslx.bwlp.sat.database.MysqlStatement;
import org.openslx.bwlp.sat.database.models.LocalImageVersion;
import org.openslx.bwlp.sat.mail.MailGenerator;
import org.openslx.bwlp.sat.permissions.User;
import org.openslx.bwlp.sat.thrift.cache.OperatingSystemList;
import org.openslx.bwlp.sat.web.VmChooserEntryXml;
import org.openslx.bwlp.sat.web.VmChooserListXml;
import org.openslx.bwlp.sat.web.XmlFilterEntry;
import org.openslx.bwlp.thrift.iface.LdapFilter;
import org.openslx.bwlp.thrift.iface.LectureRead;
import org.openslx.bwlp.thrift.iface.LectureSummary;
import org.openslx.bwlp.thrift.iface.LectureWrite;
import org.openslx.bwlp.thrift.iface.NetRule;
import org.openslx.bwlp.thrift.iface.NetShare;
import org.openslx.bwlp.thrift.iface.TNotFoundException;
import org.openslx.bwlp.thrift.iface.UserInfo;
import org.openslx.util.Json;
import org.openslx.util.Util;
import org.openslx.virtualization.configuration.VirtualizationConfiguration;
import org.openslx.virtualization.configuration.data.ConfigurationDataDozModServerToStatelessClient;
import org.openslx.virtualization.configuration.logic.ConfigurationLogicDozModServerToStatelessClient;
import org.openslx.virtualization.configuration.transformation.TransformationException;

import com.google.gson.JsonParseException;

public class DbLecture {

	private static final Logger LOGGER = LogManager.getLogger(DbLecture.class);

	static {
		Json.registerThriftClass(NetRule.class);
		Json.registerThriftClass(NetShare.class);
	}

	private static void setWriteFields(MysqlStatement stmt, String lectureId, LectureWrite lecture,
			UserInfo updatingUser) throws SQLException {
		if (lecture.lectureName.length() > 100) {
			lecture.lectureName = lecture.lectureName.substring(0, 100);
		}
		String nicsJson = null;
		if (lecture.nics != null && !lecture.nics.isEmpty()) {
			for (;;) {
				nicsJson = Json.serialize(lecture.nics);
				if (nicsJson.length() < 200)
					break;
				lecture.nics.remove(0);
			}
		}
		String netruleJson;
		if (lecture.networkExceptions == null) {
			netruleJson = null;
		} else {
			netruleJson = Json.serialize(lecture.networkExceptions);
		}
		stmt.setString("lectureid", lectureId);
		stmt.setString("displayname", lecture.lectureName);
		stmt.setString("description", lecture.description);
		stmt.setString("imageversionid", lecture.imageVersionId);
		stmt.setBoolean("autoupdate", lecture.autoUpdate);
		stmt.setBoolean("isenabled", lecture.isEnabled);
		stmt.setBoolean("isprivate", lecture.limitToAllowedUsers);
		stmt.setBoolean("islocationprivate", lecture.limitToLocations);
		stmt.setLong("starttime", lecture.startTime);
		stmt.setLong("endtime", lecture.endTime);
		stmt.setString("updaterid", updatingUser.userId);
		stmt.setString("runscript", lecture.runscript);
		stmt.setString("nics", nicsJson);
		stmt.setString("netrules", netruleJson);
		stmt.setBoolean("isexam", lecture.isExam);
		stmt.setBoolean("hasinternetaccess", lecture.hasInternetAccess);
		stmt.setBoolean("hasusbaccess", lecture.hasUsbAccess);
		stmt.setBoolean("caneditdefault", lecture.defaultPermissions.edit);
		stmt.setBoolean("canadmindefault", lecture.defaultPermissions.admin);
	}

	private static void writeLocations(MysqlConnection connection, String lectureId, List<Integer> locationIds)
			throws SQLException {
		MysqlStatement delStmt = connection.prepareStatement("DELETE FROM lecture_x_location WHERE lectureid = :lectureid");
		delStmt.setString("lectureid", lectureId);
		delStmt.executeUpdate();
		if (locationIds == null || locationIds.isEmpty())
			return;
		MysqlStatement addStmt = connection.prepareStatement("INSERT IGNORE INTO lecture_x_location (lectureid, locationid)"
				+ " VALUES (:lectureid, :locationid)");
		addStmt.setString("lectureid", lectureId);
		for (Integer locationId : locationIds) {
			addStmt.setInt("locationid", locationId);
			addStmt.executeUpdate();
		}
	}

	public static String create(UserInfo user, LectureWrite lecture) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("INSERT INTO lecture"
					+ " (lectureid, displayname, description, imageversionid, autoupdate,"
					+ "  isenabled, starttime, endtime, createtime, updatetime,"
					+ "  isprivate, islocationprivate,"
					+ "  ownerid, updaterid, runscript, nics, netrules, isexam,"
					+ "  hasinternetaccess, hasusbaccess, caneditdefault, canadmindefault)"
					+ "         VALUES             "
					+ " (:lectureid, :displayname, :description, :imageversionid, :autoupdate,"
					+ "  :isenabled, :starttime, :endtime, UNIX_TIMESTAMP(), UNIX_TIMESTAMP(),"
					+ "  :isprivate, :islocationprivate,"
					+ "  :ownerid, :updaterid, :runscript, :nics, :netrules, :isexam,"
					+ "  :hasinternetaccess, :hasusbaccess, :caneditdefault, :canadmindefault)");
			String lectureId = UUID.randomUUID().toString();
			setWriteFields(stmt, lectureId, lecture, user);
			stmt.setString("ownerid", user.userId);
			stmt.executeUpdate();
			writeLocations(connection, lectureId, lecture.locationIds);
			if (lecture.isSetNetworkShares()) {
				DbLectureNetshare.writeForLecture(connection, lectureId, lecture.networkShares);
			}
			if (lecture.isSetLdapFilters()) {
				DbLectureFilter.writeForLectureLdap(connection, lectureId, lecture.ldapFilters);
			}
			connection.commit();
			return lectureId;
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbLecture.create()", e);
			throw e;
		}
	}

	private static void update(MysqlConnection connection, UserInfo user, String lectureId,
			LectureWrite lecture) throws SQLException {
		MysqlStatement stmt = connection.prepareStatement("UPDATE lecture SET "
				+ " displayname = :displayname, description = :description, imageversionid = :imageversionid,"
				+ " autoupdate = :autoupdate, isenabled = :isenabled, starttime = :starttime,"
				+ " endtime = :endtime, updatetime = UNIX_TIMESTAMP(),"
				+ " isprivate = :isprivate, islocationprivate = :islocationprivate,"
				+ " updaterid = :updaterid, runscript = :runscript, nics = :nics,"
				+ " netrules = :netrules, isexam = :isexam, hasinternetaccess = :hasinternetaccess, hasusbaccess = :hasusbaccess,"
				+ " caneditdefault = :caneditdefault, canadmindefault = :canadmindefault"
				+ " WHERE lectureid = :lectureid");
		setWriteFields(stmt, lectureId, lecture, user);
		writeLocations(connection, lectureId, lecture.locationIds);
		if (lecture.isSetNetworkShares()) {
			DbLectureNetshare.writeForLecture(connection, lectureId, lecture.networkShares);
		}
		if (lecture.isSetLdapFilters()) {
			DbLectureFilter.writeForLectureLdap(connection, lectureId, lecture.ldapFilters);
		}
		if (lecture.isSetPresetScriptIds()) {
			DbRunScript.writeLectureRunScripts(connection, lectureId, lecture.presetScriptIds);
		}
		if (lecture.isSetPresetNetworkExceptionIds()) {
			DbLectureNetworkRules.writeLectureNetworkExceptions(connection, lectureId, lecture.presetNetworkExceptionIds);
		}
		stmt.executeUpdate();
	}

	public static void update(UserInfo user, String lectureId, LectureWrite lecture) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			update(connection, user, lectureId, lecture);
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbLecture.update()", e);
			throw e;
		}
	}

	public static void setOwner(UserInfo user, String lectureId, String newOwnerId) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("UPDATE lecture"
					+ " SET ownerid = :ownerid, updaterid = :updaterid, updatetime = UNIX_TIMESTAMP()"
					+ " WHERE lectureid = :lectureid");
			stmt.setString("ownerid", newOwnerId);
			stmt.setString("updaterid", user.userId);
			stmt.setString("lectureid", lectureId);
			stmt.executeUpdate();
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbLecture.setOwner()", e);
			throw e;
		}
	}

	private static LectureSummary fillSummary(UserInfo user, ResultSet rs) throws SQLException {
		LectureSummary lecture = new LectureSummary();
		lecture.setLectureId(rs.getString("lectureid"));
		lecture.setLectureName(rs.getString("lecturename"));
		lecture.setDescription(rs.getString("description"));
		lecture.setImageVersionId(rs.getString("imageversionid"));
		lecture.setImageBaseId(rs.getString("imagebaseid"));
		lecture.setIsEnabled(rs.getBoolean("isenabled"));
		lecture.setStartTime(rs.getLong("starttime"));
		lecture.setEndTime(rs.getLong("endtime"));
		lecture.setLastUsed(rs.getLong("lastused"));
		lecture.setUseCount(rs.getInt("usecount"));
		lecture.setOwnerId(rs.getString("ownerid"));
		lecture.setUpdaterId(rs.getString("updaterid"));
		lecture.setIsExam(rs.getBoolean("isexam"));
		lecture.setHasInternetAccess(rs.getBoolean("hasinternetaccess"));
		lecture.setHasUsbAccess(rs.getBoolean("hasusbaccess"));
		lecture.setDefaultPermissions(DbLecturePermissions.fromResultSetDefault(rs));
		lecture.setUserPermissions(DbLecturePermissions.fromResultSetUser(rs));
		lecture.setIsImageVersionUsable(rs.getBoolean("imgvalid"));
		if (user != null) {
			User.setCombinedUserPermissions(lecture, user);
		}
		return lecture;
	}

	private static final String summaryBaseSql = "SELECT"
			+ " l.lectureid, l.displayname AS lecturename, l.description, l.imageversionid, i.imagebaseid,"
			+ " l.isenabled, l.starttime, l.endtime, l.lastused, l.usecount, l.ownerid, l.updaterid,"
			+ " l.isexam, l.hasinternetaccess, l.hasusbaccess, l.caneditdefault, l.canadmindefault,"
			+ " i.isvalid AS imgvalid, perm.canedit, perm.canadmin"
			+ "                      FROM lecture l                 "
			+ " LEFT JOIN imageversion i USING (imageversionid)"
			+ " LEFT JOIN lecturepermission perm ON (perm.lectureid = l.lectureid AND perm.userid = :userid)";

	public static LectureSummary getLectureSummary(UserInfo user, String lectureId) throws SQLException,
			TNotFoundException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement(summaryBaseSql
					+ " WHERE l.lectureid = :lectureid");
			stmt.setString("lectureid", lectureId);
			stmt.setString("userid", user == null ? "-" : user.userId);
			ResultSet rs = stmt.executeQuery();
			if (!rs.next())
				throw new TNotFoundException();
			return fillSummary(user, rs);
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbLecture.getLectureSummary()", e);
			throw e;
		}
	}

	public static List<LectureSummary> getAll(UserInfo user, int page) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement(summaryBaseSql
					+ (User.isStudent(user) ? " WHERE i.isrestricted = 0" : ""));
			stmt.setString("userid", user == null ? "-" : user.userId);
			ResultSet rs = stmt.executeQuery();
			List<LectureSummary> list = new ArrayList<>(100);
			while (rs.next()) {
				list.add(fillSummary(user, rs));
			}
			return list;
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbLecture.getAll()", e);
			throw e;
		}
	}

	protected static List<LectureSummary> getAllUsingImageBase(MysqlConnection connection,
			String imageBaseId, boolean autoUpdateOnly) throws SQLException {
		MysqlStatement stmt = connection.prepareStatement(summaryBaseSql
				+ " WHERE imagebaseid = :imagebaseid" + (autoUpdateOnly ? " AND autoupdate = 1" : ""));
		stmt.setString("imagebaseid", imageBaseId);
		stmt.setString("userid", "-");
		ResultSet rs = stmt.executeQuery();
		List<LectureSummary> list = new ArrayList<>();
		while (rs.next()) {
			list.add(fillSummary(null, rs));
		}
		return list;
	}

	protected static List<LectureSummary> getAllUsingImageVersion(MysqlConnection connection,
			String imageVersionId, boolean enabledOnly) throws SQLException {
		String query = summaryBaseSql + " WHERE i.imageversionid = :imageversionid";
		if (enabledOnly) {
			query += " AND l.isenabled = 1";
		}
		MysqlStatement stmt = connection.prepareStatement(query);
		stmt.setString("imageversionid", imageVersionId);
		stmt.setString("userid", "-");
		ResultSet rs = stmt.executeQuery();
		List<LectureSummary> list = new ArrayList<>();
		while (rs.next()) {
			list.add(fillSummary(null, rs));
		}
		return list;
	}
	
	private static List<NetRule> decodeNetrules(String netrules) {
		if (netrules == null)
			return null;
		try {
			NetRule[] rules = Json.deserialize(netrules, NetRule[].class);
			if (rules != null)
				return Arrays.asList(rules);
		} catch (JsonParseException e) {
			LOGGER.warn("Could not deserialize netrules", e);
		}
		return null;
	}

	public static LectureRead getLectureDetails(UserInfo user, String lectureId) throws SQLException,
			TNotFoundException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("SELECT"
					+ " l.lectureid, l.displayname AS lecturename, l.description, l.imageversionid, i.imagebaseid,"
					+ " l.autoupdate, l.isenabled, l.starttime, l.endtime, l.lastused, l.usecount, l.createtime,"
					+ " l.updatetime, l.ownerid, l.updaterid, l.runscript, l.nics, l.netrules, l.isexam,"
					+ " l.isprivate, l.islocationprivate, l.hasinternetaccess, l.hasusbaccess,"
					+ " l.caneditdefault, l.canadmindefault, p.canedit, p.canadmin, n.sharedata"
					+ "                   FROM lecture l            "
					+ " LEFT JOIN imageversion i USING (imageversionid)"
					+ " LEFT JOIN lecturepermission p ON (l.lectureid = p.lectureid AND p.userid = :userid)"
					+ " LEFT JOIN networkshare n ON (l.lectureid = n.lectureid)"
					+ " WHERE l.lectureid = :lectureid LIMIT 1");
			stmt.setString("userid", user == null ? "" : user.userId);
			stmt.setString("lectureid", lectureId);
			ResultSet rs = stmt.executeQuery();
			if (!rs.next())
				throw new TNotFoundException();
			LectureRead lecture = new LectureRead();
			lecture.setLectureId(rs.getString("lectureid"));
			lecture.setLectureName(rs.getString("lecturename"));
			lecture.setDescription(rs.getString("description"));
			lecture.setImageVersionId(rs.getString("imageversionid"));
			lecture.setImageBaseId(rs.getString("imagebaseid"));
			lecture.setAutoUpdate(rs.getBoolean("autoupdate"));
			lecture.setIsEnabled(rs.getBoolean("isenabled"));
			lecture.setLimitToAllowedUsers(rs.getBoolean("isprivate"));
			lecture.setLimitToLocations(rs.getBoolean("islocationprivate"));
			lecture.setStartTime(rs.getLong("starttime"));
			lecture.setEndTime(rs.getLong("endtime"));
			lecture.setLastUsed(rs.getLong("lastused"));
			lecture.setUseCount(rs.getInt("usecount"));
			lecture.setCreateTime(rs.getLong("createtime"));
			lecture.setUpdateTime(rs.getLong("updatetime"));
			lecture.setOwnerId(rs.getString("ownerid"));
			lecture.setUpdaterId(rs.getString("updaterid"));
			lecture.setRunscript(rs.getString("runscript"));
			lecture.setNics(null); // TODO fill nics
			lecture.setNetworkExceptions(decodeNetrules(rs.getString("netrules")));
			lecture.setPresetNetworkExceptionIds(DbLectureNetworkRules.getForEdit(connection, lectureId));
			lecture.setIsExam(rs.getBoolean("isexam"));
			lecture.setHasInternetAccess(rs.getBoolean("hasinternetaccess"));
			lecture.setHasUsbAccess(rs.getBoolean("hasusbaccess"));
			lecture.setAllowedUsers(getAllowedUsers(connection, lectureId));
			lecture.setDefaultPermissions(DbLecturePermissions.fromResultSetDefault(rs));
			lecture.setUserPermissions(DbLecturePermissions.fromResultSetUser(rs));
			User.setCombinedUserPermissions(lecture, user);
			lecture.setLocationIds(DbLocation.getLectureLocations(connection, lectureId));
			lecture.setNetworkShares(new ArrayList<NetShare>());
			lecture.setPresetNetworkShares(new ArrayList<Integer>());
			DbLectureNetshare.getSplitForLecture(connection, lectureId,
					lecture.networkShares, lecture.presetNetworkShares);
			lecture.setLdapFilters(new ArrayList<LdapFilter>());
			lecture.setPresetLdapFilters(new ArrayList<Integer>());
			DbLectureFilter.getSplitForLectureLdap(connection, lectureId,
					lecture.ldapFilters, lecture.presetLdapFilters);
			lecture.setPresetScriptIds(DbRunScript.getForEdit(connection, lectureId));
			return lecture;
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbLecture.getLectureDetails()", e);
			throw e;
		}
	}

	public static List<String> getAllowedUsers(MysqlConnection connection, String lectureId)
			throws SQLException {
		MysqlStatement stmt = connection.prepareStatement("SELECT" + " userlogin FROM lectureuser"
				+ " WHERE lectureid = :lectureid");
		stmt.setString("lectureid", lectureId);
		ResultSet rs = stmt.executeQuery();
		List<String> list = new ArrayList<>();
		while (rs.next()) {
			list.add(rs.getString("userlogin"));
		}
		return list;
	}

	public static boolean delete(String lectureId) throws TNotFoundException, SQLException {
		int affected;
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("DELETE FROM lecture WHERE lectureid = :lectureid");
			stmt.setString("lectureid", lectureId);
			affected = stmt.executeUpdate();
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbLecture.delete()", e);
			throw e;
		}
		return affected == 1;
	}

	/**
	 * Called when a new version for an image is uploaded. Update all lectures
	 * using the same base image which have the autoUpdate-flag set.
	 * 
	 * @param connection mysql connection to use
	 * @param imageBaseId base image that got a new version
	 * @param newVersion the latest (valid) version
	 * @throws SQLException
	 */
	protected static void autoUpdateUsedImage(MysqlConnection connection, String imageBaseId,
			LocalImageVersion newVersion) throws SQLException {
		if (newVersion == null)
			return;
		List<LectureSummary> lectures = getAllUsingImageBase(connection, imageBaseId, true);
		if (lectures.isEmpty())
			return;
		// Remove lectures that are already on the given latest version from the list...
		for (Iterator<LectureSummary> it = lectures.iterator(); it.hasNext();) {
			LectureSummary lecture = it.next();
			if (lecture.imageVersionId.equals(newVersion.imageVersionId))
				it.remove();
		}
		// Update lectures in DB
		MysqlStatement stmt = connection.prepareStatement("UPDATE lecture l, imageversion v SET"
				+ " l.imageversionid = :imageversionid"
				+ " WHERE v.imageversionid = l.imageversionid AND v.imagebaseid = :imagebaseid"
				+ " AND l.autoupdate = 1");
		stmt.setString("imageversionid", newVersion.imageVersionId);
		stmt.setString("imagebaseid", imageBaseId);
		stmt.executeUpdate();
		// Send informative mail to lecture admins
		MailGenerator.lectureAutoUpdate(lectures, newVersion);
	}

	/**
	 * Called when an image version is deleted or marked for deletion, so that
	 * linking lectures switch over to other available versions.
	 */
	protected static void forcefullySwitchUsedImage(MysqlConnection connection, LocalImageVersion oldVersion,
			LocalImageVersion newVersion) throws TNotFoundException, SQLException {
		if (oldVersion == newVersion
				|| (newVersion != null && newVersion.imageVersionId.equals(oldVersion.imageVersionId)))
			return;
		// First, get list of lectures using the image version to switch away from
		List<LectureSummary> lectures = getAllUsingImageVersion(connection, oldVersion.imageVersionId, true);
		if (lectures.isEmpty())
			return;
		MysqlStatement stmt;
		if (newVersion == null) {
			stmt = connection.prepareStatement("UPDATE lecture SET isenabled = 0 WHERE imageversionid = :oldversionid");
			stmt.setString("oldversionid", oldVersion.imageVersionId);
			MailGenerator.lectureDeactivated(lectures);
		} else {
			// Update and send info mail
			stmt = connection.prepareStatement("UPDATE lecture SET imageversionid = :newversionid"
					+ " WHERE imageversionid = :oldversionid");
			stmt.setString("oldversionid", oldVersion.imageVersionId);
			stmt.setString("newversionid", newVersion.imageVersionId);
			MailGenerator.lectureForcedUpdate(lectures, newVersion);
		}
		stmt.executeUpdate();
	}

	protected static void deletePermanently(MysqlConnection connection, LocalImageVersion image)
			throws SQLException {
		MysqlStatement stmt = connection.prepareStatement("DELETE FROM lecture WHERE imageversionid = :imageversionid");
		stmt.setString("imageversionid", image.imageVersionId);
		stmt.executeUpdate();
	}

	public static List<LectureSummary> getExpiringLectures(int maxRemainingDays) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement(summaryBaseSql + " WHERE endtime < :deadline");
			stmt.setString("userid", "-");
			stmt.setLong("deadline", Util.unixTime() + (maxRemainingDays * 86400));
			ResultSet rs = stmt.executeQuery();
			List<LectureSummary> list = new ArrayList<>();
			while (rs.next()) {
				list.add(fillSummary(null, rs));
			}
			return list;
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbLecture.getExpiringLectures()", e);
			throw e;
		}
	}

	public static VmChooserListXml getUsableListXml(boolean exams, String locationsString)
			throws SQLException {
		// Sanitize and clean locations string
		// Input is in the form of "1 2 3 4" or "1" or "  1   4 5"
		// We want "1,2,3,4" or "1" or "1,4,5"
		// Do this since we embed this directly into the query
		String cleanLocations = null;
		if (Util.isEmptyString(locationsString)) {
			cleanLocations = "0";
		} else if (locationsString.indexOf(' ') == -1) {
			cleanLocations = Integer.toString(org.openslx.util.Util.parseInt(locationsString, 0));
		} else {
			String[] array = locationsString.split(" +");
			for (String loc : array) {
				int val = org.openslx.util.Util.parseInt(loc, -1);
				if (val == -1)
					continue;
				if (cleanLocations == null) {
					cleanLocations = Integer.toString(val);
				} else {
					cleanLocations += "," + Integer.toString(val);
				}
			}
			if (cleanLocations == null) {
				cleanLocations = "0";
			}
		}
		// Query
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("SELECT"
					+ " l.lectureid, l.displayname AS lecturename, l.description,"
					+ " l.islocationprivate, loc.lectureid AS loctest,"
					+ " l.endtime, l.usecount, o.displayname AS osname, v.virtname, b.istemplate,"
					+ " v.virtid, ov.virtoskeyword, i.filepath"
					+ "                      FROM lecture l                 "
					+ " INNER JOIN imageversion i USING (imageversionid)"
					+ " INNER JOIN imagebase b USING (imagebaseid)"
					+ " INNER JOIN operatingsystem o USING (osid)"
					+ " INNER JOIN virtualizer v USING (virtid)"
					+ " LEFT JOIN os_x_virt ov USING (osid, virtid)"
					+ " LEFT JOIN ("
					+ "  SELECT DISTINCT lectureid FROM lecture_x_location WHERE locationid IN ("
					+ cleanLocations
					+ ")"
					+ " ) loc USING (lectureid)"
					+ " WHERE l.isenabled = 1 AND l.isprivate = 0 AND l.isexam = :isexam"
					+ " AND l.starttime < UNIX_TIMESTAMP() AND l.endtime > UNIX_TIMESTAMP() AND i.isvalid = 1");
			stmt.setBoolean("isexam", exams);
			ResultSet rs = stmt.executeQuery();
			VmChooserListXml list = new VmChooserListXml(true);
			while (rs.next()) {
				boolean isForThisLocation = rs.getString("loctest") != null;
				if (!isForThisLocation && rs.getBoolean("islocationprivate"))
					continue; // Is limited to location, and we're not in one of the required locations
				String lectureId = rs.getString("lectureid");
				boolean isTemplate = rs.getBoolean("istemplate");
				int prio = 100;
				// Get ldap filters
				List<XmlFilterEntry> ldapFilters = DbLectureFilter.getFiltersXml(connection, lectureId);
				list.add(new VmChooserEntryXml(rs.getString("filepath"), prio, "-",
						rs.getString("lecturename"), rs.getString("description"), lectureId,
						rs.getString("virtid"), rs.getString("virtname"), rs.getString("virtoskeyword"),
						rs.getString("osname"), "", isForThisLocation, isTemplate, ldapFilters));
			}
			return list;
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbLecture.getUsableList()", e);
			throw e;
		}
	}

	public static LaunchData getClientLaunchData(String lectureId) throws SQLException,
			TNotFoundException {
		LaunchData retval = new LaunchData();
		byte[] config;

		try (MysqlConnection connection = Database.getConnection()) {
			// Get required data about lecture and used image
			MysqlStatement stmt = connection.prepareStatement("SELECT"
					+ " l.displayname AS lecturename, l.starttime, l.endtime, l.isenabled, l.hasusbaccess,"
					+ " l.runscript, b.osid, o.virtoskeyword, i.virtualizerconfig"
					+ "                      FROM lecture l                 "
					+ " INNER JOIN imageversion i USING (imageversionid)"
					+ " INNER JOIN imagebase b USING (imagebaseid)"
					+ " LEFT JOIN os_x_virt o USING (osid, virtid)" + " WHERE l.lectureid = :lectureid");
			stmt.setString("lectureid", lectureId);
			ResultSet rs = stmt.executeQuery();
			long now = Util.unixTime();
			if (!rs.next() || !rs.getBoolean("isenabled") || rs.getLong("starttime") > now
					|| rs.getLong("endtime") < now) {
				throw new TNotFoundException();
			}

			config = rs.getBytes("virtualizerconfig");
			if (config == null) {
				return null;
			}

			final String lectureName = rs.getString("lecturename");
			final String osKeyword = rs.getString("virtoskeyword");
			final boolean usbAccess = rs.getBoolean("hasusbaccess");

			// prepare virtualization configuration as part of the launch data
			VirtualizationConfiguration virtualizationConfig = null;
			try {
				virtualizationConfig = VirtualizationConfiguration.getInstance(OperatingSystemList.get(), config, config.length);
			} catch (Exception e) {
				LOGGER.error("Virtualization configuration could not be initialized", e);
				return null;
			}

			// modify virtualization configuration
			byte[] configuration = null;
			try {
				final ConfigurationLogicDozModServerToStatelessClient downloadLogic = new ConfigurationLogicDozModServerToStatelessClient();
				downloadLogic.apply(virtualizationConfig,
						new ConfigurationDataDozModServerToStatelessClient(lectureName, osKeyword, usbAccess));
				configuration = virtualizationConfig.getConfigurationAsByteArray();
			} catch (TransformationException e) {
				LOGGER.error("Virtualization configuration could not be modified", e);
				return null;
			}

			retval.configuration = configuration;
			retval.legacyRunScript = rs.getString("runscript");
			retval.netShares = DbLectureNetshare.getCombinedForLecture(connection, lectureId);
			retval.runScript = DbRunScript.getRunScriptsForLaunch(connection, lectureId, rs.getInt("osid"));

			// Everything worked so far, update statistics counters
			MysqlStatement upStmt = connection.prepareStatement("UPDATE"
					+ " lecture SET lastused = UNIX_TIMESTAMP(), usecount = usecount + 1"
					+ " WHERE lectureid = :lectureid");
			upStmt.setString("lectureid", lectureId);
			upStmt.executeUpdate();
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbLecture.getClientLaunchData()", e);
			throw e;
		}

		return retval;
	}

	public static boolean getFirewallRules(String lectureId, List<NetRule> list) throws SQLException, TNotFoundException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("SELECT netrules, hasinternetaccess FROM lecture"
					+ " WHERE lectureid = :lectureid");
			stmt.setString("lectureid", lectureId);
			ResultSet rs = stmt.executeQuery();
			if (!rs.next())
				throw new TNotFoundException();
			NetRule[] json = Json.deserialize(rs.getString("netrules"), NetRule[].class);
			if (json != null && json.length != 0) {
				list.addAll(Arrays.asList(json));
			}
			List<NetRule> others = DbLectureNetworkRules.getForStartup(connection, lectureId);
			if (others != null) {
				list.addAll(others);
			}
			return rs.getBoolean("hasinternetaccess");
		} catch (SQLException e) {
			LOGGER.error("Query failed in getClientLaunchNetworkExceptions()", e);
			throw e;
		}
	}

	public static void deleteOld(int minAgeDays) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("DELETE FROM lecture WHERE endtime < :cutoff");
			stmt.setLong("cutoff", Util.unixTime() - TimeUnit.DAYS.toSeconds(minAgeDays));
			stmt.executeUpdate();
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbLecture.deleteOld()", e);
			throw e;
		}
	}

	public static List<LectureSummary> getLecturesUsingImageVersion(MysqlConnection connection, String imageVersionId)
	throws SQLException {
		MysqlStatement stmt = connection.prepareStatement(summaryBaseSql
				+ " WHERE l.imageversionid = :imageversionid");
		stmt.setString("userid", "-");
		stmt.setString("imageversionid", imageVersionId);
		ResultSet rs = stmt.executeQuery();
		List<LectureSummary> list = new ArrayList<>();
		while (rs.next()) {
			list.add(fillSummary(null, rs));
		}
		return list;
	}

	public static List<LectureSummary> getLecturesUsingImageVersion(String imageVersionId)
			throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			return getLecturesUsingImageVersion(connection, imageVersionId);
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbLecture.getExpiringLectures()", e);
			throw e;
		}
	}

	public static void unlinkFromImageVersion(String imageVersionId) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			unlinkFromImageVersion(connection, imageVersionId);
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbLecture.unlinkFromImageVersion()", e);
			throw e;
		}
	}

	protected static void unlinkFromImageVersion(MysqlConnection connection, String imageVersionId)
			throws SQLException {
		List<LectureSummary> lectures = getLecturesUsingImageVersion(connection, imageVersionId);
		MysqlStatement uStmt = connection.prepareStatement("UPDATE lecture SET imageversionid = NULL"
				+ " WHERE imageversionid = :imageversionid");
		uStmt.setString("imageversionid", imageVersionId);
		uStmt.executeUpdate();
		MailGenerator.lectureDeactivated(lectures);
	}
	
	public static class RunScript {
		public final String content;
		public final String extension;
		public final int visibility;
		public final boolean passCreds;
		RunScript(String content, String extension, int visibility, boolean passCreds) {
			this.content = content;
			this.extension = extension;
			this.visibility = visibility;
			this.passCreds = passCreds;
		}
	}
	
	public static class LaunchData {
		public byte[] configuration;
		public List<NetShare> netShares;
		public String legacyRunScript;
		public List<RunScript> runScript;
	}

}
