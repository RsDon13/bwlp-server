package org.openslx.bwlp.sat.mail;

import java.sql.SQLException;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.bwlp.sat.database.mappers.DbConfiguration;
import org.openslx.bwlp.sat.database.mappers.DbImage;
import org.openslx.bwlp.sat.database.mappers.DbImagePermissions;
import org.openslx.bwlp.sat.database.mappers.DbLecture;
import org.openslx.bwlp.sat.database.mappers.DbLecturePermissions;
import org.openslx.bwlp.sat.database.mappers.DbUser;
import org.openslx.bwlp.sat.database.mappers.DbUser.User;
import org.openslx.bwlp.sat.database.models.LocalImageVersion;
import org.openslx.bwlp.sat.mail.MailQueue.MailConfig;
import org.openslx.bwlp.sat.mail.MailTemplatePlain.Template;
import org.openslx.bwlp.sat.util.Formatter;
import org.openslx.bwlp.thrift.iface.ImageDetailsRead;
import org.openslx.bwlp.thrift.iface.ImagePermissions;
import org.openslx.bwlp.thrift.iface.ImageVersionDetails;
import org.openslx.bwlp.thrift.iface.LecturePermissions;
import org.openslx.bwlp.thrift.iface.LectureSummary;
import org.openslx.bwlp.thrift.iface.ShareMode;
import org.openslx.bwlp.thrift.iface.TNotFoundException;
import org.openslx.bwlp.thrift.iface.UserInfo;
import org.openslx.util.Util;

public class MailGenerator {

	private static final Logger LOGGER = LogManager.getLogger(MailGenerator.class);

	/**
	 * Called when an image has been updated, and linked lectures will be moved
	 * to the new image version.
	 * 
	 * @param lectures
	 *            List of affected lectures
	 * @param newVersion
	 *            version id of new image
	 * @throws SQLException
	 */
	public static void lectureAutoUpdate(List<LectureSummary> lectures, LocalImageVersion newVersion)
			throws SQLException {
		if (!hasMailConfig())
			return;
		
		String imageName;
		try {
			ImageDetailsRead image = DbImage.getImageDetails(null, newVersion.imageBaseId);
			imageName = image.imageName;
		} catch (TNotFoundException | SQLException e) {
			LOGGER.warn("Could not get image details for image version " + newVersion.imageVersionId);
			imageName = "(unbekannt)";
		}
		
		String uploaderName;
		try {
			User u = DbUser.getCached(newVersion.uploaderId);
			uploaderName = Formatter.userFullName(u.ui) + " <" + u.ui.eMail + ">";
		} catch (TNotFoundException e) {
			LOGGER.warn("Could not get uploading user of new image version " + newVersion.imageVersionId);
			uploaderName = "(unbekannt)";
		}
		
		for (LectureSummary lecture : lectures) {
			List<UserInfo> relevantUsers = getUserToMail(lecture);

			MailTemplate template = DbConfiguration.getMailTemplate(Template.LECTURE_UPDATED);
			Map<String, String> templateArgs = new HashMap<>();
			templateArgs.put("lecture", lecture.lectureName);
			templateArgs.put("image", imageName);
			templateArgs.put("created", Formatter.date(newVersion.createTime));
			templateArgs.put("uploader", uploaderName);

			String msg = template.format(templateArgs);

			for (UserInfo user : relevantUsers) {
				/* Don't notice about changes by user */
				if (newVersion.uploaderId.equals(user.userId))
					continue;
				MailQueue.queue(new Mail(user, wordWrap(msg)));
			}
		}
	}

	/**
	 * Called when a lecture is downgraded, or a lecture is updated and it
	 * doesn't have auto-updates enabled.
	 * 
	 * @param lectures
	 *            list of affected lectures
	 * @param newVersion
	 *            the new version being switched to
	 * @throws SQLException
	 */
	public static void lectureForcedUpdate(List<LectureSummary> lectures, LocalImageVersion newVersion)
			throws SQLException {
		if (!hasMailConfig())
			return;
		for (LectureSummary lecture : lectures) {
			List<UserInfo> relevantUsers = getUserToMail(lecture);

			MailTemplate template = DbConfiguration.getMailTemplate(Template.LECTURE_FORCED_UPDATE);
			Map<String, String> templateArgs = new HashMap<>();
			templateArgs.put("lecture", lecture.lectureName);
			templateArgs.put("created", Formatter.date(newVersion.createTime));
			templateArgs.put("date", Formatter.date(newVersion.createTime));

			String msg = template.format(templateArgs);

			
			for (UserInfo user : relevantUsers) {
				MailQueue.queue(new Mail(user, wordWrap(msg)));
			}
		}
	}

	public static void lectureDeactivated(List<LectureSummary> lectures) {
		if (!hasMailConfig())
			return;
		for (LectureSummary lecture : lectures) {
			List<UserInfo> relevantUsers = getUserToMail(lecture);
			
			ImageDetailsRead image;
			try {
				image = DbImage.getImageDetails(null, lecture.imageBaseId);
			} catch (TNotFoundException | SQLException e) {
				LOGGER.warn("Could not get image details for image version " + lecture.imageVersionId);
				return;
			}
			
			MailTemplate template = DbConfiguration.getMailTemplate(Template.LECTURE_DEACTIVATED);
			Map<String, String> templateArgs = new HashMap<>();
			templateArgs.put("lecture", lecture.lectureName);
			templateArgs.put("image", image.imageName);
			
			String msg = template.format(templateArgs);
			
			for (UserInfo user : relevantUsers) {
				MailQueue.queue(new Mail(user, wordWrap(msg)));
			}
		}
	}

	public static void sendImageVersionDeleted(String imageBaseId, LocalImageVersion oldLocal, LocalImageVersion newLocal) {
		if (!hasMailConfig())
			return;
		ImageDetailsRead image;
		try {
			image = DbImage.getImageDetails(null, imageBaseId);
		} catch (TNotFoundException | SQLException e) {
			LOGGER.warn("Version Deleted for image=" + imageBaseId + " failed", e);
			return;
		}
		ImageVersionDetails oldVersion = null;
		ImageVersionDetails newVersion = null;
		for (ImageVersionDetails version : image.versions) {
			if (oldLocal != null && version.versionId.equals(oldLocal.imageVersionId)) {
				oldVersion = version;
			}
			if (newLocal != null && version.versionId.equals(newLocal.imageVersionId)) {
				newVersion = version;
			}
		}
		
		MailTemplate template;
		Map<String, String> templateArgs = new HashMap<>();
				
		if (oldVersion == newVersion) {
			return;
		}
		templateArgs.put("image", image.imageName);
		
		if (newVersion == null) {
			template = DbConfiguration.getMailTemplate(Template.VM_DELETED_LAST_VERSION);
		} else {
			template = DbConfiguration.getMailTemplate(Template.VM_DELETED_OLD_VERSION);
			String uploaderName;
			try {
				User uploader = DbUser.getCached(newVersion.uploaderId);
				uploaderName = Formatter.userFullName(uploader.ui) + " <" + uploader.ui.eMail + ">";
			} catch (TNotFoundException | SQLException e) {
				uploaderName = "(unbekannt)";
			}
			templateArgs.put("uploader", uploaderName);
			templateArgs.put("new_created", Formatter.date(newVersion.createTime));
			
			if (oldVersion != null) {
				templateArgs.put("old_created", Formatter.date(oldVersion.createTime));
			}
		}		
		
		List<UserInfo> relevantUsers = getUserToMail(image);
		for (UserInfo user : relevantUsers) {
			MailQueue.queue(new Mail(user, wordWrap(template.format(templateArgs))));
		}
	}

	public static void sendImageDeletionReminder(LocalImageVersion version, int days, boolean mailForced) {
		if (!hasMailConfig())
			return;
		ImageDetailsRead image;
		try {
			image = DbImage.getImageDetails(null, version.imageBaseId);
		} catch (TNotFoundException | SQLException e) {
			LOGGER.warn("Could not get image details for image version " + version.imageVersionId);
			return;
		}
		boolean isCurrentlyLatest = image.latestVersionId == null
				|| image.latestVersionId.equals(version.imageVersionId);
			
		MailTemplate template;
		
		if (isCurrentlyLatest) {
			template = DbConfiguration.getMailTemplate(Template.VM_CURRENT_VERSION_EXPIRING);
		} else if (mailForced) {
			template = DbConfiguration.getMailTemplate(Template.VM_OLD_VERSION_EXPIRING);
		} else {
			return;
		}
		List<UserInfo> relevantUsers;
		// Mail users responsible for this image
		Map<String, String> templateArgs = new HashMap<>();
		templateArgs.put("image", image.imageName);
		templateArgs.put("remaining_days", String.valueOf(days));
		templateArgs.put("created", Formatter.date(version.createTime));
		templateArgs.put("image_expiretime", Formatter.date(version.expireTime));
		String message = wordWrap(template.format(templateArgs));
		relevantUsers = getUserToMail(image);
		for (UserInfo user : relevantUsers) {
			MailQueue.queue(new Mail(user, message));
		}
		// Mail users using this image for a lecture, but only if the image
		// expires before the lecture ends
		// And the image to delete is currently the newest image
		if (!isCurrentlyLatest)
			return;
		List<LectureSummary> lectures;
		try {
			lectures = DbLecture.getLecturesUsingImageVersion(version.imageVersionId);
		} catch (SQLException e) {
			lectures = new ArrayList<>(0);
		}
		for (LectureSummary lecture : lectures) {
			if (lecture.endTime < version.expireTime) {
				continue;
			}
			template = DbConfiguration.getMailTemplate(Template.LECTURE_LINKED_VM_EXPIRING);
			templateArgs.put("lecture", lecture.lectureName);
			message = wordWrap(template.format(templateArgs));
			relevantUsers = getUserToMail(lecture);
			for (UserInfo user : relevantUsers) {
				MailQueue.queue(new Mail(user, message));
			}
		}
	}

	public static void sendLectureExpiringReminder(LectureSummary lecture, int days) {
		if (!hasMailConfig())
			return;
		List<UserInfo> relevantUsers = getUserToMail(lecture);
		MailTemplate template = DbConfiguration.getMailTemplate(Template.LECTURE_EXPIRING);
		Map<String, String> templateArgs = new HashMap<>();
		templateArgs.put("lecture", lecture.lectureName);
		templateArgs.put("remaining_days", String.valueOf(days));
		templateArgs.put("lecture_endtime", Formatter.date(lecture.endTime));
		
		String message = template.format(templateArgs);
		
		for (UserInfo user : relevantUsers) {
			MailQueue.queue(new Mail(user, wordWrap(message)));
		}
	}

	public static boolean isValidMailConfig(MailConfig conf) {
		return conf != null && conf.port != 0 && !Util.isEmptyString(conf.host)
				&& !Util.isEmptyString(conf.senderAddress);
	}

	private static boolean hasMailConfig() {
		MailConfig conf;
		try {
			conf = DbConfiguration.getMailConfig();
		} catch (SQLException e) {
			return false;
		}
		return isValidMailConfig(conf);
	}

	private static List<UserInfo> getUserToMail(LectureSummary lecture) {
		Map<String, LecturePermissions> users;
		try {
			users = DbLecturePermissions.getForLecture(lecture.lectureId, false);
		} catch (SQLException e) {
			users = new HashMap<>();
		}
		users.put(lecture.ownerId, new LecturePermissions(true, true));
		List<UserInfo> list = new ArrayList<>(users.size());
		for (Entry<String, LecturePermissions> entry : users.entrySet()) {
			LecturePermissions perms = entry.getValue();
			if (!perms.admin && !perms.edit)
				continue;
			User user;
			try {
				user = DbUser.getCached(entry.getKey());
			} catch (TNotFoundException e) {
				LOGGER.warn("UserID " + entry.getKey() + " unknown");
				continue;
			} catch (SQLException e) {
				continue; // Logging happened in DbUser
			}
			if (user.local.emailNotifications) {
				list.add(user.ui);
			}
		}
		return list;
	}

	private static List<UserInfo> getUserToMail(ImageDetailsRead image) {
		Map<String, ImagePermissions> users;
		try {
			users = DbImagePermissions.getForImageBase(image.imageBaseId, false);
		} catch (SQLException e) {
			users = new HashMap<>();
		}
		// For images downloaded from master server, don't email the owner, as the owner
		// is from some other organization (usually)
		if (image.shareMode == ShareMode.LOCAL || image.shareMode == ShareMode.PUBLISH) {
			users.put(image.ownerId, new ImagePermissions(true, true, true, true));
		}
		List<UserInfo> list = new ArrayList<>(users.size());
		for (Entry<String, ImagePermissions> entry : users.entrySet()) {
			ImagePermissions perms = entry.getValue();
			if (!perms.admin && !perms.edit)
				continue;
			User user;
			try {
				user = DbUser.getCached(entry.getKey());
			} catch (TNotFoundException e) {
				LOGGER.warn("UserID " + entry.getKey() + " unknown");
				continue;
			} catch (SQLException e) {
				continue; // Logging happened in DbUser
			}
			if (user.local.emailNotifications) {
				list.add(user.ui);
			}
		}
		return list;
	}

	private static String wordWrap(String input) {
		return wordWrap(input, 76, "\n  ", Locale.GERMAN);
	}

	private static String wordWrap(String input, int width, String nlString, Locale locale) {
		if (input == null) {
			return "";
		} else if (width < 5) {
			return input;
		} else if (width >= input.length()) {
			return input;
		}

		StringBuilder buf = new StringBuilder(input);
		boolean endOfLine = false;
		int lineStart = 0;

		for (int i = 0; i < buf.length(); i++) {
			if (buf.charAt(i) == '\n') {
				lineStart = i + 1;
				endOfLine = true;
			}

			// handle splitting at width character
			if (i > lineStart + width - 1) {
				if (endOfLine) {
					buf.insert(i, nlString);
					lineStart = i + nlString.length();
					endOfLine = false;
				} else {
					int limit = i - lineStart - 1;
					BreakIterator breaks = BreakIterator.getLineInstance(locale);
					breaks.setText(buf.substring(lineStart, i));
					int end = breaks.last();

					// if the last character in the search string isn't a space,
					// we can't split on it (looks bad). Search for a previous
					// break character
					if (end == limit + 1) {
						if (!Character.isWhitespace(buf.charAt(lineStart + end))) {
							end = breaks.preceding(end - 1);
						}
					}

					// if the last character is a space, replace it with a \n
					if (end != BreakIterator.DONE && end == limit + 1) {
						buf.replace(lineStart + end, lineStart + end + 1, nlString.substring(0, 1));
						buf.insert(lineStart + end + 1, nlString.substring(1));
						lineStart = lineStart + end + nlString.length() - 1;
					}
					// otherwise, just insert a \n
					else if (end != BreakIterator.DONE && end != 0) {
						buf.insert(lineStart + end, nlString);
						lineStart = lineStart + end + nlString.length();
					} else {
						buf.insert(i, nlString);
						lineStart = i + nlString.length();
					}
				}
			}
		}

		return buf.toString();
	}

}
