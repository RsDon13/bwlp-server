package org.openslx.bwlp.sat.thrift;

import java.io.File;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.thrift.TException;
import org.openslx.bwlp.sat.RuntimeConfig;
import org.openslx.bwlp.sat.SupportedFeatures;
import org.openslx.bwlp.sat.database.mappers.DbImage;
import org.openslx.bwlp.sat.database.mappers.DbImage.DeleteState;
import org.openslx.bwlp.sat.database.mappers.DbImagePermissions;
import org.openslx.bwlp.sat.database.mappers.DbLecture;
import org.openslx.bwlp.sat.database.mappers.DbLectureFilter;
import org.openslx.bwlp.sat.database.mappers.DbLectureNetshare;
import org.openslx.bwlp.sat.database.mappers.DbLectureNetworkRules;
import org.openslx.bwlp.sat.database.mappers.DbLecturePermissions;
import org.openslx.bwlp.sat.database.mappers.DbLocation;
import org.openslx.bwlp.sat.database.mappers.DbLog;
import org.openslx.bwlp.sat.database.mappers.DbRunScript;
import org.openslx.bwlp.sat.database.mappers.DbUser;
import org.openslx.bwlp.sat.database.models.ImageVersionMeta;
import org.openslx.bwlp.sat.database.models.LocalImageVersion;
import org.openslx.bwlp.sat.database.models.LocalUser;
import org.openslx.bwlp.sat.fileserv.FileServer;
import org.openslx.bwlp.sat.fileserv.IncomingDataTransfer;
import org.openslx.bwlp.sat.fileserv.OutgoingDataTransfer;
import org.openslx.bwlp.sat.fileserv.SyncTransferHandler;
import org.openslx.bwlp.sat.maintenance.DeleteOldImages;
import org.openslx.bwlp.sat.permissions.User;
import org.openslx.bwlp.sat.thrift.cache.OperatingSystemList;
import org.openslx.bwlp.sat.thrift.cache.OrganizationList;
import org.openslx.bwlp.sat.thrift.cache.VirtualizerList;
import org.openslx.bwlp.sat.util.FileSystem;
import org.openslx.bwlp.sat.util.Formatter;
import org.openslx.bwlp.sat.util.Sanitizer;
import org.openslx.bwlp.thrift.iface.AuthorizationError;
import org.openslx.bwlp.thrift.iface.DateParamError;
import org.openslx.bwlp.thrift.iface.ImageBaseWrite;
import org.openslx.bwlp.thrift.iface.ImageDetailsRead;
import org.openslx.bwlp.thrift.iface.ImagePermissions;
import org.openslx.bwlp.thrift.iface.ImagePublishData;
import org.openslx.bwlp.thrift.iface.ImageSummaryRead;
import org.openslx.bwlp.thrift.iface.ImageVersionDetails;
import org.openslx.bwlp.thrift.iface.ImageVersionWrite;
import org.openslx.bwlp.thrift.iface.InvocationError;
import org.openslx.bwlp.thrift.iface.LecturePermissions;
import org.openslx.bwlp.thrift.iface.LectureRead;
import org.openslx.bwlp.thrift.iface.LectureSummary;
import org.openslx.bwlp.thrift.iface.LectureWrite;
import org.openslx.bwlp.thrift.iface.Location;
import org.openslx.bwlp.thrift.iface.OperatingSystem;
import org.openslx.bwlp.thrift.iface.Organization;
import org.openslx.bwlp.thrift.iface.PredefinedData;
import org.openslx.bwlp.thrift.iface.SatelliteConfig;
import org.openslx.bwlp.thrift.iface.SatelliteServer;
import org.openslx.bwlp.thrift.iface.SatelliteStatus;
import org.openslx.bwlp.thrift.iface.SatelliteUserConfig;
import org.openslx.bwlp.thrift.iface.ShareMode;
import org.openslx.bwlp.thrift.iface.TAuthorizationException;
import org.openslx.bwlp.thrift.iface.TInvalidDateParam;
import org.openslx.bwlp.thrift.iface.TInvalidTokenException;
import org.openslx.bwlp.thrift.iface.TInvocationException;
import org.openslx.bwlp.thrift.iface.TNotFoundException;
import org.openslx.bwlp.thrift.iface.TTransferRejectedException;
import org.openslx.bwlp.thrift.iface.TransferInformation;
import org.openslx.bwlp.thrift.iface.TransferStatus;
import org.openslx.bwlp.thrift.iface.UploadOptions;
import org.openslx.bwlp.thrift.iface.UserInfo;
import org.openslx.bwlp.thrift.iface.Virtualizer;
import org.openslx.bwlp.thrift.iface.WhoamiInfo;
import org.openslx.sat.thrift.version.Version;
import org.openslx.thrifthelper.Comparators;
import org.openslx.thrifthelper.ThriftManager;
import org.openslx.util.ThriftUtil;
import org.openslx.util.Util;

public class ServerHandler implements SatelliteServer.Iface {

	private static final Logger LOGGER = LogManager.getLogger(ServerHandler.class);

	private static final FileServer fileServer = FileServer.instance();

	@Override
	public long getVersion(long clientVersion) {
		if (clientVersion >= Version.VERSION)
			return Version.VERSION;
		return Math.max(clientVersion, Version.MIN_VERSION);
	}
	
	@Override
	public String getSupportedFeatures() {
		return SupportedFeatures.getFeatureString();
	}

	@Override
	public SatelliteConfig getConfiguration() {
		return RuntimeConfig.get();
	}

	/*
	 * File Transfer
	 */

	@Override
	public TransferInformation requestImageVersionUpload(String userToken, String imageBaseId, long fileSize,
			List<ByteBuffer> blockHashes, ByteBuffer machineDescription) throws TTransferRejectedException,
			TAuthorizationException, TInvocationException, TNotFoundException, TException {
		UserInfo user = SessionManager.getOrFail(userToken);
		if (!FileSystem.waitForStorage())
			throw new TInvocationException(InvocationError.INTERNAL_SERVER_ERROR, "VM storage not mounted");
		User.canEditBaseImageOrFail(user, imageBaseId);
		ImageDetailsRead image;
		try {
			image = DbImage.getImageDetails(user, imageBaseId);
		} catch (SQLException e) {
			throw new TInvocationException();
		}
		if (image.shareMode != ShareMode.LOCAL && image.shareMode != ShareMode.PUBLISH)
			throw new TAuthorizationException(AuthorizationError.NO_PERMISSION,
					"Cannot upload new versions of a replicated image");
		// Unwrap machine description
		byte[] mDesc = ThriftUtil.unwrapByteBuffer(machineDescription);
		// Unwrap sha1sum list
		List<byte[]> hashList = ThriftUtil.unwrapByteBufferList(blockHashes);
		IncomingDataTransfer transfer = fileServer.createNewUserUpload(user, image, fileSize, hashList, mDesc);
		return new TransferInformation(transfer.getId(), fileServer.getPlainPort(), fileServer.getSslPort());
	}

	@Override
	public void updateBlockHashes(String uploadToken, List<ByteBuffer> blockHashes, String userToken)
			throws TInvalidTokenException {
		// TODO: Validate user token some time in the future
		IncomingDataTransfer upload = fileServer.getUploadByToken(uploadToken);
		if (upload == null)
			throw new TInvalidTokenException();
		List<byte[]> hashList = ThriftUtil.unwrapByteBufferList(blockHashes);
		upload.updateBlockHashList(hashList);
	}

	@Override
	public UploadOptions setUploadOptions(String userToken, String uploadToken, UploadOptions options)
			throws TAuthorizationException, TInvalidTokenException, TException {
		IncomingDataTransfer upload = fileServer.getUploadByToken(uploadToken);
		if (upload == null)
			throw new TInvalidTokenException();
		if (options == null) // Query only -- don't validate user
			return upload.setOptions(null);
		UserInfo user = SessionManager.getOrFail(userToken);
		if (Comparators.user.compare(user, upload.getOwner()) != 0)
			throw new TAuthorizationException(AuthorizationError.NO_PERMISSION, "This isn't your upload");
		return upload.setOptions(options);
	}

	@Override
	public void cancelUpload(String uploadToken) {
		IncomingDataTransfer upload = fileServer.getUploadByToken(uploadToken);
		if (upload != null) {
			LOGGER.debug("User is cancelling upload " + uploadToken);
			upload.cancel();
		}

	}

	@Override
	public TransferStatus queryUploadStatus(String uploadToken) throws TInvalidTokenException {
		IncomingDataTransfer upload = fileServer.getUploadByToken(uploadToken);
		if (upload == null) {
			// It might be a master -> sat transfer...
			upload = SyncTransferHandler.getDownloadByToken(uploadToken);
		}
		if (upload == null)
			throw new TInvalidTokenException();
		return upload.getStatus();
	}

	@Override
	public TransferInformation requestDownload(String userToken, String imageVersionId)
			throws TAuthorizationException, TInvocationException, TNotFoundException,
			TTransferRejectedException {
		UserInfo user = SessionManager.getOrFail(userToken);
		if (!FileSystem.waitForStorage())
			throw new TInvocationException(InvocationError.INTERNAL_SERVER_ERROR, "VM storage not mounted");
		ImageVersionMeta imageVersion;
		try {
			imageVersion = DbImage.getVersionDetails(imageVersionId);
		} catch (SQLException e) {
			throw new TInvocationException();
		}
		User.canDownloadImageVersionOrFail(user, imageVersion.imageBaseId, imageVersionId);
		OutgoingDataTransfer transfer;
		try {
			transfer = fileServer.createNewUserDownload(DbImage.getLocalImageData(imageVersionId));
		} catch (SQLException e) {
			throw new TInvocationException();
		}
		TransferInformation ti = new TransferInformation(transfer.getId(), fileServer.getPlainPort(),
				fileServer.getSslPort());
		ti.setBlockHashes(imageVersion.sha1sums);
		ti.setMachineDescription(imageVersion.machineDescription);
		return ti;
	}

	@Override
	public void cancelDownload(String downloadToken) {
		OutgoingDataTransfer download = fileServer.getDownloadByToken(downloadToken);
		if (download != null)
			download.cancel();
	}

	/*
	 * Authentication/Validation
	 */

	@Override
	public void isAuthenticated(String userToken) throws TAuthorizationException, TInvocationException {
		SessionManager.ensureAuthenticated(userToken);
	}

	@Override
	public WhoamiInfo whoami(String userToken) throws TAuthorizationException, TInvocationException {
		UserInfo user = SessionManager.getOrFail(userToken);
		return new WhoamiInfo(user, User.isSuperUser(user), User.canListImages(user));
	}

	@Override
	public void invalidateSession(String userToken) {
		SessionManager.remove(userToken);
	}

	/*
	 * Query basic information which doesn't require authentication
	 */

	@Override
	public List<OperatingSystem> getOperatingSystems() {
		return OperatingSystemList.get();
	}

	@Override
	public List<Virtualizer> getVirtualizers() {
		return VirtualizerList.get();
	}

	@Override
	public List<Organization> getAllOrganizations() {
		return OrganizationList.get();
	}

	@Override
	public SatelliteStatus getStatus() {
		return new SatelliteStatus(FileSystem.getAvailableStorageBytes(), Util.unixTime());
	}

	/*
	 * Everything below required at least a valid session
	 */

	@Override
	public SatelliteUserConfig getUserConfig(String userToken) throws TAuthorizationException,
			TInvocationException {
		UserInfo user = SessionManager.getOrFail(userToken);
		LocalUser localData;
		try {
			localData = DbUser.getLocalData(user);
		} catch (SQLException e) {
			throw new TInvocationException();
		}
		return new SatelliteUserConfig(localData != null && localData.emailNotifications);
	}

	@Override
	public void setUserConfig(String userToken, SatelliteUserConfig config) throws TAuthorizationException,
			TInvocationException {
		UserInfo user = SessionManager.getOrFail(userToken);
		try {
			DbUser.writeUserConfig(user, config);
		} catch (SQLException e) {
			throw new TInvocationException();
		}
	}

	@Override
	public List<ImageSummaryRead> getImageList(String userToken, List<String> tagSearch, int page)
			throws TAuthorizationException, TInvocationException {
		UserInfo user = SessionManager.getOrFail(userToken);
		User.canListImagesOrFail(user);
		try {
			return DbImage.getAllVisible(user, tagSearch, page);
		} catch (SQLException e) {
			throw new TInvocationException();
		}
	}

	@Override
	public ImageDetailsRead getImageDetails(String userToken, String imageBaseId)
			throws TAuthorizationException, TNotFoundException, TInvocationException {
		UserInfo user = SessionManager.getOrFail(userToken);
		
		try {
			return DbImage.getImageDetails(user, imageBaseId);
		} catch (SQLException e) {
			throw new TInvocationException();
		}
	}

	@Override
	public String createImage(String userToken, String imageName) throws TAuthorizationException,
			TInvocationException {
		UserInfo user = SessionManager.getOrFail(userToken);
		User.canCreateImageOrFail(user);
		if (!Util.isPrintable(imageName) || Util.isEmptyString(imageName))
			throw new TInvocationException(InvocationError.INVALID_DATA, "Invalid or empty name");
		try {
			String imageBaseId = DbImage.createImage(user, imageName);
			DbLog.log(user, imageBaseId, "New Image created: '" + imageName + "'");
			return imageBaseId;
		} catch (SQLException e) {
			throw new TInvocationException();
		}
	}

	@Override
	public void updateImageBase(String userToken, String imageBaseId, ImageBaseWrite newData)
			throws TAuthorizationException, TInvocationException, TNotFoundException {
		UserInfo user = SessionManager.getOrFail(userToken);
		User.canEditBaseImageOrFail(user, imageBaseId);
		// Check image name for invalid characters
		if (!Util.isPrintable(newData.imageName) || Util.isEmptyString(newData.imageName))
			throw new TInvocationException(InvocationError.INVALID_DATA, "Invalid or empty name");
		// Check if image is marked for replication. If so, only allow changing the syncmode to FROZEN/DOWNLOAD
		try {
			ImageSummaryRead imageSummary = DbImage.getImageSummary(user, imageBaseId);
			if (imageSummary.shareMode == ShareMode.DOWNLOAD || imageSummary.shareMode == ShareMode.FROZEN) {
				if (newData.shareMode != ShareMode.DOWNLOAD && newData.shareMode != ShareMode.FROZEN) {
					throw new TInvocationException(InvocationError.INVALID_SHARE_MODE,
							"Cannot change share mode from remote to local");
				} else {
					// Share mode is valid and changed, but ignore all other fields
					DbImage.setShareMode(imageBaseId, newData);
					return;
				}
				// (Unreachable)
			} else {
				// Likewise, if share mode is local or publish, don't allow changing to FROZEN/DOWNLOAD
				if (newData.shareMode != ShareMode.LOCAL && newData.shareMode != ShareMode.PUBLISH) {
					throw new TInvocationException(InvocationError.INVALID_SHARE_MODE,
							"Cannot change share mode from local to remote");
				}
			}
			// TODO: Should other fields be validated? Most fields should be protected by fk constraints,
			// but the user would only get a generic error, with no hint at the actual problem.
			// The update routine will make sure only the super user can change the template flag
			newData.defaultPermissions = Sanitizer.handleImagePermissions(newData.defaultPermissions);
			DbImage.updateImageMetadata(user, imageBaseId, newData);
		} catch (SQLException e1) {
			throw new TInvocationException();
		}
	}

	@Override
	public void updateImageVersion(String userToken, String imageVersionId, ImageVersionWrite image)
			throws TAuthorizationException, TInvocationException, TNotFoundException {
		UserInfo user = SessionManager.getOrFail(userToken);
		// Special case: Version is still being uploaded, so there's no entry yet - remember for later
		IncomingDataTransfer upload = fileServer.getUploadByToken(imageVersionId);
		if (upload != null && upload.setVersionData(user, image)) {
			return;
		}
		// Normal case - version already exists
		User.canEditImageVersionOrFail(user, imageVersionId);
		try {
			// Do not allow editing remote images
			ImageSummaryRead imageSummary = DbImage.getImageSummary(user,
					DbImage.getBaseIdForVersionId(imageVersionId));
			if (imageSummary.shareMode == ShareMode.DOWNLOAD || imageSummary.shareMode == ShareMode.FROZEN) {
				throw new TAuthorizationException(AuthorizationError.NO_PERMISSION,
						"Cannot edit image coming from master server");
			}
			DbImage.updateImageVersion(user, imageVersionId, image);
		} catch (SQLException e1) {
			throw new TInvocationException();
		}
	}

	@Override
	public void deleteImageVersion(String userToken, String imageVersionId) throws TAuthorizationException,
			TNotFoundException, TInvocationException {
		UserInfo user = SessionManager.getOrFail(userToken);
		if (FileServer.instance().isActiveTransfer(null, imageVersionId)
				|| SyncTransferHandler.isActiveTransfer(null, imageVersionId))
			throw new TInvocationException(InvocationError.INVALID_DATA, "Image is currently in use");
		if (!FileSystem.waitForStorage())
			throw new TInvocationException(InvocationError.INTERNAL_SERVER_ERROR, "VM storage not mounted");
		User.canDeleteImageVersionOrFail(user, imageVersionId);
		try {
			ImageSummaryRead imageSummary = DbImage.getImageSummary(user,
					DbImage.getBaseIdForVersionId(imageVersionId));
			DbImage.markForDeletion(imageVersionId);
			DbImage.setDeletion(DeleteState.WANT_DELETE, imageVersionId);
			DbLog.log(user, imageSummary.imageBaseId, Formatter.userFullName(user) + " deleted Version "
					+ imageVersionId + " of '" + imageSummary.imageName + "' (" + imageSummary.imageBaseId
					+ ")");
		} catch (SQLException e) {
			throw new TInvocationException();
		}
		DeleteOldImages.hardDeleteImagesAsync();
	}

	@Override
	public void deleteImageBase(String userToken, String imageBaseId) throws TAuthorizationException,
			TNotFoundException, TInvocationException {
		UserInfo user = SessionManager.getOrFail(userToken);
		if (FileServer.instance().isActiveTransfer(imageBaseId, null)
				|| SyncTransferHandler.isActiveTransfer(imageBaseId, null))
			throw new TInvocationException(InvocationError.INVALID_DATA, "Image is currently in use");
		ImageDetailsRead imageDetails;
		try {
			imageDetails = DbImage.getImageDetails(user, imageBaseId);
		} catch (SQLException e) {
			throw new TInvocationException();
		}
		User.canDeleteImageOrFail(imageDetails);
		String[] ids = new String[imageDetails.versions.size()];
		int index = 0;
		for (ImageVersionDetails version : imageDetails.versions) {
			if (version.versionId != null) {
				if (FileServer.instance().isActiveTransfer(null, version.versionId)
						|| SyncTransferHandler.isActiveTransfer(null, version.versionId))
					throw new TInvocationException(InvocationError.INVALID_DATA, "Image is currently in use");
				ids[index++] = version.versionId;
			}
		}
		if (index != 0) {
			try {
				DbImage.markForDeletion(ids);
				DbImage.setDeletion(DeleteState.WANT_DELETE, ids);
			} catch (Exception e) {
				LOGGER.warn("Could not delete version when trying to delete base image", e);
			}
			DeleteOldImages.hardDeleteImages();
		}
		DbLog.log(user, imageDetails.imageBaseId, Formatter.userFullName(user) + " deleted Image '" + imageDetails.imageName
				+ "' with all its versions (" + index + ")");
		try {
			DbImage.deleteBasePermanently(imageBaseId);
		} catch (SQLException e) {
			throw new TInvocationException();
		}
	}

	@Override
	public void writeImagePermissions(String userToken, String imageBaseId,
			Map<String, ImagePermissions> permissions) throws TAuthorizationException, TNotFoundException,
			TInvocationException {
		UserInfo user = SessionManager.getOrFail(userToken);
		User.canEditImagePermissionsOrFail(user, imageBaseId);
		try {
			DbImagePermissions.writeForImageBase(imageBaseId, permissions);
		} catch (SQLException e) {
			throw new TInvocationException();
		}
	}

	@Override
	public Map<String, ImagePermissions> getImagePermissions(String userToken, String imageBaseId)
			throws TAuthorizationException, TNotFoundException, TInvocationException {
		UserInfo user = SessionManager.getOrFail(userToken);
		boolean adminOnly = !User.canEditImagePermissions(user, imageBaseId);
		try {
			return DbImagePermissions.getForImageBase(imageBaseId, adminOnly);
		} catch (SQLException e) {
			throw new TInvocationException();
		}
	}

	@Override
	public void setImageOwner(String userToken, String imageBaseId, String newOwnerId)
			throws TAuthorizationException, TNotFoundException, TInvocationException, TException {
		UserInfo user = SessionManager.getOrFail(userToken);
		User.canChangeImageOwnerOrFail(user, imageBaseId);
		try {
			ImageSummaryRead imageSummary = DbImage.getImageSummary(user, imageBaseId);
			if (imageSummary.shareMode == ShareMode.DOWNLOAD || imageSummary.shareMode == ShareMode.FROZEN) {
				throw new TAuthorizationException(AuthorizationError.NO_PERMISSION,
						"Cannot change owner of image that gets downloaded from master server");
			}
			DbImage.setImageOwner(imageBaseId, newOwnerId, user);
			UserInfo newOwner = DbUser.getOrNull(newOwnerId);
			DbLog.log(user, imageBaseId, Formatter.userFullName(user) + " changed owner of '"
					+ imageSummary.imageName + "' to " + Formatter.userFullName(newOwner));
			DbLog.log(user, newOwnerId, Formatter.userFullName(newOwner) + " was declared new owner of '"
					+ imageSummary.imageName + "' by " + Formatter.userFullName(user));
		} catch (SQLException e) {
			throw new TInvocationException();
		}
	}

	@Override
	public void setImageVersionExpiry(String userToken, String imageVersionId, long expireTime)
			throws TAuthorizationException, TNotFoundException, TInvocationException, TInvalidDateParam {
		long now = Util.unixTime();
		if (expireTime > now + 3 * RuntimeConfig.getMaxImageValiditySeconds())
			throw new TInvalidDateParam(DateParamError.TOO_HIGH, "Expire date too far in the future");
		if (expireTime < now - 365 * 86400)
			throw new TInvalidDateParam(DateParamError.TOO_LOW, "Expire date too far in the past");
		UserInfo user = SessionManager.getOrFail(userToken);
		User.canChangeImageExpireDateOrFail(user);
		LocalImageVersion localImageData;
		try {
			localImageData = DbImage.getLocalImageData(imageVersionId);
		} catch (SQLException e) {
			throw new TInvocationException();
		}
		File srcFile = FileSystem.composeAbsoluteImagePath(localImageData);
		String errorMessage = null;
		if (srcFile == null) {
			errorMessage = "File has invalid path on server";
		} else if (!srcFile.canRead()) {
			errorMessage = "File missing on server";
		} else if (srcFile.length() != localImageData.fileSize) {
			errorMessage = "File corrupted on server";
		}
		try {
			if (errorMessage == null) {
				DbImage.setDeletion(DeleteState.KEEP, localImageData.imageVersionId);
				DbImage.setExpireDate(localImageData.imageVersionId, expireTime);
				DbImage.markValid(true, false, localImageData);
			} else {
				DbImage.markValid(false, false, localImageData);
				throw new TInvocationException(InvocationError.INTERNAL_SERVER_ERROR, errorMessage);
			}
		} catch (SQLException e) {
			throw new TInvocationException();
		}
	}

	@Override
	public String requestImageReplication(String userToken, String imageVersionId)
			throws TAuthorizationException, TNotFoundException, TInvocationException {
		UserInfo user = SessionManager.getOrFail(userToken);
		User.canTriggerReplicationOrFail(user, imageVersionId);
		if (!FileSystem.waitForStorage())
			throw new TInvocationException(InvocationError.INTERNAL_SERVER_ERROR, "VM storage not mounted");
		// Query master server
		ImagePublishData imagePublishData;
		try {
			imagePublishData = ThriftManager.getMasterClient().getImageData(userToken, imageVersionId);
		} catch (TException e) {
			LOGGER.error("Could not query image data from master server for"
					+ " an image that a client wants to replicate", e);
			throw new TInvocationException(InvocationError.INTERNAL_SERVER_ERROR,
					"Cannot query master server for image information");
		}
		// Known by master server; now update/write to DB
		try {
			if (imagePublishData.owner == null) {
				imagePublishData.owner = imagePublishData.uploader;
			}
			DbUser.writeUserOnReplication(imagePublishData.owner);
			DbImage.writeBaseImage(imagePublishData);
			DbImagePermissions.writeForImageBase(imagePublishData.imageBaseId, user.userId,
					new ImagePermissions(true, true, true, true));
		} catch (SQLException e) {
			throw new TInvocationException(InvocationError.INTERNAL_SERVER_ERROR,
					"Could not write to local DB");
		}
		imagePublishData.uploader = user;
		String transferId = SyncTransferHandler.requestImageDownload(userToken, imagePublishData);
		DbLog.log(user, imagePublishData.imageBaseId, Formatter.userFullName(user)
				+ " triggered download from master server of version " + imageVersionId + " of '"
				+ imagePublishData.imageName + "'");
		return transferId;
	}

	@Override
	public String publishImageVersion(String userToken, String imageVersionId)
			throws TAuthorizationException, TNotFoundException, TInvocationException,
			TTransferRejectedException {
		UserInfo user = SessionManager.getOrFail(userToken);
		User.canCreateImageOrFail(user);
		LocalImageVersion imgVersion = null;
		ImageSummaryRead imgBase = null;
		try {
			imgVersion = DbImage.getLocalImageData(imageVersionId);
			imgBase = DbImage.getImageSummary(user, imgVersion.imageBaseId);
			//img.uploaderId
		} catch (SQLException e1) {
			throw new TInvocationException(InvocationError.INTERNAL_SERVER_ERROR, "Database error");
		}
		User.canUploadToMasterOrFail(user, imgBase);
		try {
			String transferId = SyncTransferHandler.requestImageUpload(userToken, imgBase, imgVersion);
			DbLog.log(user, imgBase.imageBaseId, Formatter.userFullName(user)
					+ " triggered upload to master server of version " + imageVersionId + " of '"
					+ imgBase.imageName + "'");
			return transferId;
		} catch (TTransferRejectedException e) {
			LOGGER.warn("Master server rejected upload of image version " + imgVersion.imageVersionId);
			throw e;
		} catch (SQLException e) {
			throw new TInvocationException(InvocationError.INTERNAL_SERVER_ERROR, "Database error");
		}
	}

	@Override
	public String createLecture(String userToken, LectureWrite lecture) throws TAuthorizationException,
			TInvocationException, TInvalidDateParam, TNotFoundException {
		if (lecture == null || lecture.defaultPermissions == null)
			throw new TInvocationException(InvocationError.MISSING_DATA, "Lecture data missing or incomplete");
		if (lecture.locationIds != null
				&& lecture.locationIds.size() > RuntimeConfig.getMaxLocationsPerLecture())
			throw new TInvocationException(InvocationError.INVALID_DATA, "Too many locations for lecture");
		UserInfo user = SessionManager.getOrFail(userToken);
		User.canCreateLectureOrFail(user);
		User.canLinkToImageOrFail(user, lecture.imageVersionId);
		Sanitizer.handleLectureDates(lecture, null);
		try {
			String lectureId = DbLecture.create(user, lecture);
			DbLog.log(user, lectureId, Formatter.userFullName(user) + " created lecture '" + lecture.lectureName + "'");
			return lectureId;
		} catch (SQLException e) {
			throw new TInvocationException();
		}
	}

	@Override
	public void updateLecture(String userToken, String lectureId, LectureWrite newLectureData)
			throws TAuthorizationException, TNotFoundException, TInvocationException, TInvalidDateParam {
		if (newLectureData == null)
			throw new TInvocationException(InvocationError.MISSING_DATA, "Lecture data missing or incomplete");
		if (newLectureData.locationIds != null
				&& newLectureData.locationIds.size() > RuntimeConfig.getMaxLocationsPerLecture())
			throw new TInvocationException(InvocationError.INVALID_DATA, "Too many locations for lecture");
		UserInfo user = SessionManager.getOrFail(userToken);
		LectureSummary oldLecture;
		try {
			oldLecture = DbLecture.getLectureSummary(user, lectureId);
		} catch (SQLException e1) {
			throw new TInvocationException();
		}
		User.canEditLectureOrFail(user, oldLecture);
		// TODO Copy empty fields in new from old
		if (oldLecture.imageVersionId == null
				|| !oldLecture.imageVersionId.equals(newLectureData.imageVersionId)) {
			User.canLinkToImageOrFail(user, newLectureData.imageVersionId);
		}
		Sanitizer.handleLectureDates(newLectureData, oldLecture);
		try {
			DbLecture.update(user, lectureId, newLectureData);
		} catch (SQLException e) {
			throw new TInvocationException();
		}
	}

	@Override
	public List<LectureSummary> getLectureList(String userToken, int page) throws TAuthorizationException,
			TInvocationException {
		UserInfo user = SessionManager.getOrFail(userToken);
		try {
			// If user is student, getAll() will only return lectures where the current linked image is not restricted
			return DbLecture.getAll(user, page);
		} catch (SQLException e) {
			throw new TInvocationException();
		}
	}

	@Override
	public LectureRead getLectureDetails(String userToken, String lectureId) throws TAuthorizationException,
			TNotFoundException, TInvocationException {
		UserInfo user = SessionManager.getOrFail(userToken);
		User.canSeeLectureDetailsOrFail(user);
		try {
			return DbLecture.getLectureDetails(user, lectureId);
		} catch (SQLException e) {
			throw new TInvocationException();
		}
	}

	@Override
	public void deleteLecture(String userToken, String lectureId) throws TAuthorizationException,
			TNotFoundException, TInvocationException {
		UserInfo user = SessionManager.getOrFail(userToken);
		User.canDeleteLectureOrFail(user, lectureId);
		try {
			LectureSummary lecture = DbLecture.getLectureSummary(user, lectureId);
			if (!DbLecture.delete(lectureId))
				throw new TNotFoundException();
			DbLog.log(user, lectureId, Formatter.userFullName(user) + " deleted lecture '" + lecture.lectureName + "'");
		} catch (SQLException e) {
			throw new TInvocationException();
		}
	}

	@Override
	public void writeLecturePermissions(String userToken, String lectureId,
			Map<String, LecturePermissions> permissions) throws TAuthorizationException, TNotFoundException,
			TInvocationException {
		UserInfo user = SessionManager.getOrFail(userToken);
		User.canEditLecturePermissionsOrFail(user, lectureId);
		try {
			DbLecturePermissions.writeForLecture(lectureId, permissions);
		} catch (SQLException e) {
			throw new TInvocationException();
		}
	}

	@Override
	public Map<String, LecturePermissions> getLecturePermissions(String userToken, String lectureId)
			throws TAuthorizationException, TNotFoundException, TInvocationException {
		UserInfo user = SessionManager.getOrFail(userToken);
		boolean adminOnly = !User.canEditLecturePermissions(user, lectureId);
		try {
			return DbLecturePermissions.getForLecture(lectureId, adminOnly);
		} catch (SQLException e) {
			throw new TInvocationException();
		}
	}

	@Override
	public void setLectureOwner(String userToken, String lectureId, String newOwnerId)
			throws TAuthorizationException, TNotFoundException, TInvocationException, TException {
		UserInfo user = SessionManager.getOrFail(userToken);
		LectureSummary lecture;
		try {
			lecture = DbLecture.getLectureSummary(user, lectureId);
		} catch (SQLException e1) {
			throw new TInvocationException();
		}
		User.canChangeLectureOwnerOrFail(user, lecture);
		try {
			DbLecture.setOwner(user, lectureId, newOwnerId);
			UserInfo newOwner = DbUser.getOrNull(newOwnerId);
			DbLog.log(user, lectureId, Formatter.userFullName(user) + " changed owner of '"
					+ lecture.lectureName + "' to " + Formatter.userFullName(newOwner));
			DbLog.log(user, newOwnerId, Formatter.userFullName(newOwner) + " was declared new owner of '"
					+ lecture.lectureName + "' by " + Formatter.userFullName(user));
		} catch (SQLException e) {
			throw new TInvocationException();
		}
	}

	@Override
	public List<UserInfo> getUserList(String userToken, int page) throws TAuthorizationException,
			TInvocationException {
		SessionManager.getOrFail(userToken);
		try {
			return DbUser.getAll(page);
		} catch (SQLException e) {
			throw new TInvocationException();
		}
	}

	@Override
	public List<Location> getLocations() throws TException {
		try {
			return DbLocation.getLocations();
		} catch (SQLException e) {
			throw new TInvocationException(InvocationError.INTERNAL_SERVER_ERROR,
					"Database failure when retrieving list");
		}
	}

	@Override
	public ByteBuffer getImageVersionVirtConfig(String userToken,
			String imageVersionId) throws TAuthorizationException,
			TNotFoundException, TInvocationException, TException {
		UserInfo user = SessionManager.getOrFail(userToken);
		User.canSeeImageDetailsOrFail(user);
		byte[] machineDescription = null;
		try {
			machineDescription = DbImage.getVirtualizerConfig(imageVersionId);
		} catch (SQLException e) {
			throw new TInvocationException(InvocationError.INTERNAL_SERVER_ERROR,
					"Database failure when retrieving the virtualizer config for '" + imageVersionId + "'.");
		}
		if (machineDescription == null)
			return null;
		return ByteBuffer.wrap(machineDescription);
	}

	@Override
	public void setImageVersionVirtConfig(String userToken, String imageVersionId,
			ByteBuffer machineDescription) throws TAuthorizationException,
			TNotFoundException, TInvocationException, TException {
		UserInfo user = SessionManager.getOrFail(userToken);
		User.canEditImageVersionOrFail(user, imageVersionId);
		byte[] mdBytes = ThriftUtil.unwrapByteBuffer(machineDescription);
		try {
			DbImage.setVirtualizerConfig(imageVersionId, mdBytes);
		} catch (SQLException e) {
			throw new TInvocationException(InvocationError.INTERNAL_SERVER_ERROR,
					"Database failure when setting the virtualizer config for '" + imageVersionId + "'.");
		}
	}

	@Override
	public PredefinedData getPredefinedData(String userToken) throws TAuthorizationException,
			TInvocationException, TException {
		SessionManager.ensureAuthenticated(userToken); // Only logged in users
		PredefinedData data = new PredefinedData();
		try {
			data.ldapFilter = DbLectureFilter.getPredefinedLdap();
		} catch (SQLException e) {
			throw new TInvocationException(InvocationError.INTERNAL_SERVER_ERROR,
					"Database failure when querying predefined LDAP filters.");
		}
		try {
			data.netShares = DbLectureNetshare.getPredefined();
		} catch (SQLException e) {
			throw new TInvocationException(InvocationError.INTERNAL_SERVER_ERROR,
					"Database failure when querying predefined network shares.");
		}
		try {
			data.runScripts = DbRunScript.getPredefinedRunScripts();
		} catch (SQLException e) {
			throw new TInvocationException(InvocationError.INTERNAL_SERVER_ERROR,
					"Database failure when querying predefined run scripts.");
		}
		try {
			data.networkExceptions = DbLectureNetworkRules.getPredefined();
		} catch (SQLException e) {
			throw new TInvocationException(InvocationError.INTERNAL_SERVER_ERROR,
					"Database failure when querying predefined network rules.");
		}
		return data;
	}

}
