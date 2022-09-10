package org.openslx.bwlp.sat.permissions;

import java.sql.SQLException;

import org.openslx.bwlp.sat.RuntimeConfig;
import org.openslx.bwlp.sat.database.mappers.DbImage;
import org.openslx.bwlp.sat.database.mappers.DbLecture;
import org.openslx.bwlp.sat.database.mappers.DbOrganization;
import org.openslx.bwlp.sat.database.models.LocalOrganization;
import org.openslx.bwlp.sat.database.models.LocalUser;
import org.openslx.bwlp.sat.thrift.cache.OrganizationList;
import org.openslx.bwlp.sat.util.Sanitizer;
import org.openslx.bwlp.thrift.iface.AuthorizationError;
import org.openslx.bwlp.thrift.iface.ImageDetailsRead;
import org.openslx.bwlp.thrift.iface.ImagePermissions;
import org.openslx.bwlp.thrift.iface.ImageSummaryRead;
import org.openslx.bwlp.thrift.iface.ImageVersionDetails;
import org.openslx.bwlp.thrift.iface.LecturePermissions;
import org.openslx.bwlp.thrift.iface.LectureRead;
import org.openslx.bwlp.thrift.iface.LectureSummary;
import org.openslx.bwlp.thrift.iface.Role;
import org.openslx.bwlp.thrift.iface.ShareMode;
import org.openslx.bwlp.thrift.iface.TAuthorizationException;
import org.openslx.bwlp.thrift.iface.TInvocationException;
import org.openslx.bwlp.thrift.iface.TNotFoundException;
import org.openslx.bwlp.thrift.iface.UserInfo;

public class User {

	private static final ImagePermissions imageSu = new ImagePermissions(true, true, true, true);

	private static final LecturePermissions lectureSu = new LecturePermissions(true, true);

	private static final LecturePermissions lectureNothing = new LecturePermissions(false, false);

	public static boolean isTutor(UserInfo user) {
		return user != null && user.role == Role.TUTOR;
	}

	public static boolean isStudent(UserInfo user) {
		return user != null && user.role == Role.STUDENT;
	}

	/**
	 * Check if given user is a local super user.
	 * 
	 * @param user
	 * @return
	 */
	public static boolean isSuperUser(UserInfo user) {
		LocalUser localData = LocalData.getLocalUser(user);
		return localData != null && localData.isSuperUser;
	}

	/**
	 * Check if given user is allowed to login to this satellite.
	 * 
	 * @param user user to check login permission for
	 * @return null if user is allowed, {@link AuthorizationError} otherwise
	 */
	public static AuthorizationError canLogin(UserInfo user) {
		// Student download allowed? If not, reject students right away
		if (!RuntimeConfig.allowStudentDownload() && user.role == Role.STUDENT)
			return AuthorizationError.ACCOUNT_SUSPENDED;
		LocalUser localData = LocalData.getLocalUser(user);
		if (localData != null) {
			if (localData.canLogin)
				return null; // User locally known, use user-specific permission
			return AuthorizationError.ACCOUNT_SUSPENDED;
		}
		// User unknown, check per-organization login permission
		LocalOrganization local = LocalData.getLocalOrganization(user.organizationId);
		if (local == null && OrganizationList.find(user.organizationId) == null)
			return AuthorizationError.INVALID_ORGANIZATION;
		// Organization known an allowed to login
		if (local != null && local.canLogin)
			return null;
		// Special case: If user is not allowed to login, check if there are no allowed
		// organizations yet. If so, automatically allow the organization of this user.
		try {
			if (DbOrganization.getLoginAllowedOrganizations().isEmpty()) {
				DbOrganization.setCanLogin(user.organizationId, true);
				return null;
			} else {
				return AuthorizationError.ORGANIZATION_SUSPENDED;
			}
		} catch (SQLException e) {
			// Ignore
		}
		return AuthorizationError.GENERIC_ERROR;
	}

	/**
	 * Checks whether the given user is allowed to create new images.
	 * Throws {@link TAuthorizationException} if permission is not granted.
	 * 
	 * @param user {@link UserInfo} instance representing the user in question
	 */
	public static void canCreateImageOrFail(UserInfo user) throws TAuthorizationException {
		if (!isTutor(user))
			throw new TAuthorizationException(AuthorizationError.NO_PERMISSION,
					"No permission to create new image");
	}

	/**
	 * Is given user allowed to edit/update the image identified by the given
	 * image base id? Throws {@link TAuthorizationException} if permission is
	 * not granted.
	 * 
	 * @param user
	 * @param imageBaseId
	 * @throws TNotFoundException
	 * @throws TInvocationException
	 * @throws TAuthorizationException
	 */
	public static void canEditBaseImageOrFail(UserInfo user, String imageBaseId) throws TInvocationException,
			TNotFoundException, TAuthorizationException {
		ImageSummaryRead image = getImageFromBaseId(user, imageBaseId);
		if (!image.userPermissions.edit) {
			throw new TAuthorizationException(AuthorizationError.NO_PERMISSION,
					"No permission to edit this image");
		}
	}

	/**
	 * Is given user allowed to edit/update the image identified by the given
	 * image version id? Throws {@link TAuthorizationException} if permission is
	 * not granted.
	 * 
	 * @param user
	 * @param imageVersionId
	 * @throws TNotFoundException
	 * @throws TInvocationException
	 * @throws TAuthorizationException
	 */
	public static void canEditImageVersionOrFail(UserInfo user, String imageVersionId)
			throws TInvocationException, TNotFoundException, TAuthorizationException {
		try {
			canEditBaseImageOrFail(user, DbImage.getBaseIdForVersionId(imageVersionId));
		} catch (SQLException e) {
			throw new TInvocationException();
		}
	}

	/**
	 * Is given user allowed to delete the image identified by the given
	 * image version id? Throws {@link TAuthorizationException} if permission is
	 * not granted.
	 * 
	 * @param user
	 * @param imageVersionId
	 * @throws TAuthorizationException
	 * @throws TNotFoundException
	 * @throws TInvocationException
	 */
	public static void canDeleteImageVersionOrFail(UserInfo user, String imageVersionId)
			throws TInvocationException, TNotFoundException, TAuthorizationException {
		ImageDetailsRead imageDetails;
		try {
			imageDetails = DbImage.getImageDetails(user, DbImage.getBaseIdForVersionId(imageVersionId));
		} catch (SQLException e) {
			throw new TInvocationException();
		}
		// Do not allow deleting remote images if share mode is set to "auto download" and
		// the version to delete is the latest
		if (imageDetails.shareMode == ShareMode.DOWNLOAD
				&& imageDetails.latestVersionId.equals(imageVersionId)) {
			throw new TAuthorizationException(AuthorizationError.NO_PERMISSION,
					"Cannot delete latest version of image if auto-download is enabled");
		}
		// Check user permissions
		if (imageDetails.userPermissions.admin)
			return;
		// User uploaded the image version in question and has edit permissions - allow
		if (imageDetails.userPermissions.edit) {
			for (ImageVersionDetails version : imageDetails.versions) {
				if (version.versionId.equals(imageVersionId) && version.uploaderId.equals(user.userId))
					return;
			}
		}
		throw new TAuthorizationException(AuthorizationError.NO_PERMISSION,
				"No permission to delete this image version");
	}

	public static void canDeleteImageOrFail(ImageDetailsRead imageDetails) throws TAuthorizationException {
		// Check user permissions
		if (imageDetails.userPermissions.admin)
			return;
		throw new TAuthorizationException(AuthorizationError.NO_PERMISSION,
				"No permission to delete this image");
	}

	public static void canDownloadImageVersionOrFail(UserInfo user, String imageBaseId, String imageVersionId)
			throws TAuthorizationException, TNotFoundException, TInvocationException {
		ImageDetailsRead image;
		try {
			if (imageBaseId == null) {
				imageBaseId = DbImage.getBaseIdForVersionId(imageVersionId);
			}
			image = DbImage.getImageDetails(user, imageBaseId);
		} catch (SQLException e) {
			throw new TInvocationException();
		}
		if (image.userPermissions.download) {
			if (isTutor(user))
				return;
			// User is unknown role or student, check version's restricted flag
			for (ImageVersionDetails version : image.versions) {
				if (!version.isRestricted && version.versionId.equals(imageVersionId))
					return;
			}
		}
		throw new TAuthorizationException(AuthorizationError.NO_PERMISSION,
				"No permission to download this image version");
	}

	/**
	 * Checks whether the given user is allowed to create new lectures.
	 * Throws {@link TAuthorizationException} if permission is not granted.
	 * 
	 * @param user {@link UserInfo} instance representing the user in question
	 */
	public static void canCreateLectureOrFail(UserInfo user) throws TAuthorizationException {
		if (!isTutor(user))
			throw new TAuthorizationException(AuthorizationError.NO_PERMISSION,
					"No permission to create new lecture");
	}

	/**
	 * Checks whether the given user can edit the permission list of the image
	 * identified by the given image base id.
	 * 
	 * @param user
	 * @param imageBaseId
	 * @return
	 * @throws TInvocationException
	 * @throws TNotFoundException
	 */
	public static boolean canEditImagePermissions(UserInfo user, String imageBaseId)
			throws TInvocationException, TNotFoundException {
		ImageSummaryRead image = getImageFromBaseId(user, imageBaseId);
		return image.userPermissions.admin;
	}

	/**
	 * Checks whether the given user can edit the permission list of the image
	 * identified by the given image base id.
	 * 
	 * @param user
	 * @param imageBaseId
	 * @throws TInvocationException
	 * @throws TNotFoundException
	 * @throws TAuthorizationException if permission is not granted.
	 */
	public static void canEditImagePermissionsOrFail(UserInfo user, String imageBaseId)
			throws TAuthorizationException, TInvocationException, TNotFoundException {
		if (!canEditImagePermissions(user, imageBaseId))
			throw new TAuthorizationException(AuthorizationError.NO_PERMISSION,
					"No permission to edit this image's permissions");
	}

	public static void canChangeImageOwnerOrFail(UserInfo user, String imageBaseId)
			throws TAuthorizationException, TInvocationException, TNotFoundException {
		// TODO: Who should be allowed to change the owner? Any admin, or just the owner?
		// Currently it's every admin, but this is open for discussion
		ImageSummaryRead image = getImageFromBaseId(user, imageBaseId);
		if (!image.userPermissions.admin) {
			throw new TAuthorizationException(AuthorizationError.NO_PERMISSION,
					"No permission to change image owner");
		}
	}

	public static void canEditLectureOrFail(UserInfo user, String lectureId) throws TInvocationException,
			TNotFoundException, TAuthorizationException {
		canEditLectureOrFail(user, getLectureFromId(user, lectureId));
	}

	public static void canEditLectureOrFail(UserInfo user, LectureSummary lecture)
			throws TAuthorizationException {
		if (!lecture.userPermissions.edit) {
			throw new TAuthorizationException(AuthorizationError.NO_PERMISSION,
					"No permission to edit this image");
		}
	}

	public static boolean canEditLecturePermissions(UserInfo user, String lectureId)
			throws TNotFoundException, TInvocationException {
		LectureSummary lecture = getLectureFromId(user, lectureId);
		return lecture.userPermissions.admin;
	}

	public static void canEditLecturePermissionsOrFail(UserInfo user, String lectureId)
			throws TAuthorizationException, TNotFoundException, TInvocationException {
		if (!canEditLecturePermissions(user, lectureId)) {
			throw new TAuthorizationException(AuthorizationError.NO_PERMISSION,
					"No permission to edit permissions");
		}
	}

	public static void canChangeLectureOwnerOrFail(UserInfo user, LectureSummary lecture)
			throws TAuthorizationException, TNotFoundException, TInvocationException {
		// TODO: Who should be allowed to change the owner? Any admin, or just the owner?
		// Currently it's every admin, but this is open for discussion
		if (!lecture.userPermissions.admin) {
			throw new TAuthorizationException(AuthorizationError.NO_PERMISSION,
					"No permission to change lecture owner");
		}
	}

	public static void canLinkToImageOrFail(UserInfo user, String imageVersionId) throws TNotFoundException,
			TInvocationException, TAuthorizationException {
		if (imageVersionId == null)
			return;
		ImageSummaryRead image = getImageFromVersionId(user, imageVersionId);
		if (!image.userPermissions.link) {
			throw new TAuthorizationException(AuthorizationError.NO_PERMISSION,
					"No permission to link to this image");
		}
	}

	public static boolean canListImages(UserInfo user) throws TAuthorizationException {
		return isTutor(user);
	}

	public static void canListImagesOrFail(UserInfo user) throws TAuthorizationException {
		if (!canListImages(user))
			throw new TAuthorizationException(AuthorizationError.NO_PERMISSION,
					"No permission to see list of images");
	}

	public static void canSeeImageDetailsOrFail(UserInfo user) throws TAuthorizationException {
		if (!isTutor(user))
			throw new TAuthorizationException(AuthorizationError.NO_PERMISSION,
					"No permission to see image details");
	}

	public static void canSeeLectureDetailsOrFail(UserInfo user) throws TAuthorizationException {
		if (!isTutor(user))
			throw new TAuthorizationException(AuthorizationError.NO_PERMISSION,
					"No permission to see lecture details");
	}

	public static void canDeleteLectureOrFail(UserInfo user, String lectureId)
			throws TAuthorizationException, TInvocationException, TNotFoundException {
		LectureSummary lecture = getLectureFromId(user, lectureId);
		if (!lecture.userPermissions.admin) {
			throw new TAuthorizationException(AuthorizationError.NO_PERMISSION,
					"No permission to delete this lecture");
		}
	}

	public static void canChangeImageExpireDateOrFail(UserInfo user) throws TAuthorizationException {
		if (!isSuperUser(user))
			throw new TAuthorizationException(AuthorizationError.NO_PERMISSION,
					"Only the super user can change the expire date of images");
	}

	public static void canUploadToMasterOrFail(UserInfo user, ImageSummaryRead imgBase) throws TAuthorizationException {
		if (isSuperUser(user))
			return;
		if (imgBase.userPermissions.admin)
			return;
		throw new TAuthorizationException(AuthorizationError.NO_PERMISSION,
				"You need to be image admin to upload to master server");
	}

	public static void canTriggerReplicationOrFail(UserInfo user, String imageVersionId)
			throws TAuthorizationException, TInvocationException {
		if (isTutor(user)) {
			ImageSummaryRead image;
			try {
				image = getImageFromVersionId(user, imageVersionId);
			} catch (TNotFoundException e) {
				// If the image is not known locally, allow replication
				return;
			}
			// If it's a remote image, or if the user has edit permissions, allow
			if (image.shareMode == ShareMode.DOWNLOAD || image.shareMode == ShareMode.FROZEN
					|| image.userPermissions.edit)
				return;
			throw new TAuthorizationException(AuthorizationError.NO_PERMISSION,
					"You cannot trigger downloading an image to the satellite server that is not in replication mode");
		}
		throw new TAuthorizationException(AuthorizationError.NO_PERMISSION,
				"Only tutors can trigger image replication");
	}

	public static void setCombinedUserPermissions(ImageSummaryRead image, UserInfo user) {
		if (hasAllImagePermissions(user, image.ownerId)) {
			image.userPermissions = imageSu;
			return;
		}
		image.userPermissions = calculateUserImagePermissions(user, image.userPermissions,
				image.defaultPermissions);
	}

	public static void setCombinedUserPermissions(ImageDetailsRead image, UserInfo user) {
		if (hasAllImagePermissions(user, image.ownerId)) {
			image.userPermissions = imageSu;
			return;
		}
		image.userPermissions = calculateUserImagePermissions(user, image.userPermissions,
				image.defaultPermissions);
	}

	public static void setCombinedUserPermissions(LectureRead lecture, UserInfo user) {
		if (user == null || user.role == Role.STUDENT) {
			lecture.userPermissions = lectureNothing;
			return;
		}
		if (hasAllLecturePermissions(user, lecture.ownerId)) {
			lecture.userPermissions = lectureSu;
			return;
		}
		if (lecture.userPermissions == null) {
			lecture.userPermissions = lecture.defaultPermissions;
		}
		lecture.userPermissions = Sanitizer.handleLecturePermissions(lecture.userPermissions);
	}

	public static void setCombinedUserPermissions(LectureSummary lecture, UserInfo user) {
		if (user == null || user.role == Role.STUDENT) {
			lecture.userPermissions = lectureNothing;
			return;
		}
		if (hasAllLecturePermissions(user, lecture.ownerId)) {
			lecture.userPermissions = lectureSu;
			return;
		}
		if (lecture.userPermissions == null) {
			lecture.userPermissions = lecture.defaultPermissions;
		}
		lecture.userPermissions = Sanitizer.handleLecturePermissions(lecture.userPermissions);
	}

	private static boolean hasAllImagePermissions(UserInfo user, String imageOwnerId) {
		if (user != null && user.role == Role.TUTOR) {
			// Check for owner
			if (user.userId.equals(imageOwnerId)) {
				return true;
			}
			// Check for super user
			LocalUser localUser = LocalData.getLocalUser(user);
			if (localUser != null && localUser.isSuperUser) {
				return true;
			}
		}
		return false;
	}

	private static ImagePermissions calculateUserImagePermissions(UserInfo user, ImagePermissions userPerms,
			ImagePermissions defPerms) {
		// Standard combining logic
		if (userPerms == null)
			userPerms = defPerms;
		// Reduce student's permissions to be safe
		if (user == null || user.role == Role.STUDENT) {
			if (userPerms.link || userPerms.admin || userPerms.edit) {
				if (userPerms == defPerms) {
					userPerms = new ImagePermissions(defPerms);
				}
				userPerms.link = false;
				userPerms.edit = false;
				userPerms.admin = false;
			}
		} else {
			userPerms = Sanitizer.handleImagePermissions(userPerms);
		}
		return userPerms;
	}

	private static boolean hasAllLecturePermissions(UserInfo user, String lectureOwnerId) {
		if (user != null && user.role == Role.TUTOR) {
			// Check for owner
			if (user.userId.equals(lectureOwnerId)) {
				return true;
			}
			// Check for super user
			LocalUser localUser = LocalData.getLocalUser(user);
			if (localUser != null && localUser.isSuperUser) {
				return true;
			}
		}
		return false;
	}

	private static ImageSummaryRead getImageFromBaseId(UserInfo user, String imageBaseId)
			throws TNotFoundException, TInvocationException {
		try {
			return DbImage.getImageSummary(user, imageBaseId);
		} catch (SQLException e) {
			throw new TInvocationException();
		}
	}

	private static ImageSummaryRead getImageFromVersionId(UserInfo user, String imageVersionId)
			throws TNotFoundException, TInvocationException {
		try {
			return DbImage.getImageSummary(user, DbImage.getBaseIdForVersionId(imageVersionId));
		} catch (SQLException e) {
			throw new TInvocationException();
		}
	}

	private static LectureSummary getLectureFromId(UserInfo user, String lectureId)
			throws TNotFoundException, TInvocationException {
		try {
			return DbLecture.getLectureSummary(user, lectureId);
		} catch (SQLException e) {
			throw new TInvocationException();
		}
	}
}
