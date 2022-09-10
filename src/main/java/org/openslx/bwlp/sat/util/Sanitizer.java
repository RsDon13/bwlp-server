package org.openslx.bwlp.sat.util;

import org.openslx.bwlp.sat.RuntimeConfig;
import org.openslx.bwlp.thrift.iface.DateParamError;
import org.openslx.bwlp.thrift.iface.ImagePermissions;
import org.openslx.bwlp.thrift.iface.LecturePermissions;
import org.openslx.bwlp.thrift.iface.LectureSummary;
import org.openslx.bwlp.thrift.iface.LectureWrite;
import org.openslx.bwlp.thrift.iface.TInvalidDateParam;

public class Sanitizer {

	/**
	 * One day in milliseconds
	 */
	private final static long ONE_DAY = 86400l;

	/**
	 * How far in the past can a date lie? Currently 180 days, no idea if anyone
	 * would ever need this feature, but don't error out right away
	 */
	private static final long LOWER_CUTOFF = 180l * ONE_DAY;

	private static final long MAX_IMAGE_EXPIRY = 10l * 365l * ONE_DAY;

	/**
	 * Sanitize start and end date of lecture.
	 * 
	 * @param newLecture new Lecture to sanitize
	 * @param oldLecture old Lecture to check for dates changes
	 * @throws TInvalidDateParam If start or end date have invalid values
	 */
	public static void handleLectureDates(LectureWrite newLecture, LectureSummary oldLecture) throws TInvalidDateParam {
		if (newLecture.startTime > newLecture.endTime)
			throw new TInvalidDateParam(DateParamError.NEGATIVE_RANGE, "Start date past end date");
		final long now = System.currentTimeMillis() / 1000;
		long lowLimit = now - LOWER_CUTOFF;
		long highLimit = now + RuntimeConfig.getMaxLectureValiditySeconds();
		if (oldLecture == null || newLecture.startTime != oldLecture.startTime) {
			if (newLecture.startTime < lowLimit)
				throw new TInvalidDateParam(DateParamError.TOO_LOW, "Start date lies in the past");
			if (newLecture.startTime > highLimit)
				throw new TInvalidDateParam(DateParamError.TOO_HIGH, "Start date lies too far in the future");
		}
		if (oldLecture == null || newLecture.endTime != oldLecture.endTime) {
			if (newLecture.endTime < lowLimit)
				throw new TInvalidDateParam(DateParamError.TOO_LOW, "End date lies in the past");
			// Bonus: If the end date is just a little bit off, silently correct it, since it might be clock
			// inaccuracies between server and client
			if (newLecture.endTime > highLimit) {
				if (newLecture.endTime - ONE_DAY > highLimit)
					throw new TInvalidDateParam(DateParamError.TOO_HIGH, "End date lies too far in the future");
				newLecture.endTime = highLimit;
			}
		}
	}

	/**
	 * Check if given image expiry date is valid. Be liberal here, since only
	 * the super user can set it, and they should know what they're doing.
	 * 
	 * @param unixTimestamp timestamp to check
	 * @throws TInvalidDateParam If the date is invalid
	 */
	public static void handleImageExpiryDate(long unixTimestamp) throws TInvalidDateParam {
		final long now = System.currentTimeMillis() / 1000;
		long lowLimit = now - LOWER_CUTOFF;
		if (unixTimestamp < lowLimit)
			throw new TInvalidDateParam(DateParamError.TOO_LOW, "Expiry date lies in the past");
		long highLimit = now + MAX_IMAGE_EXPIRY;
		if (unixTimestamp > highLimit)
			throw new TInvalidDateParam(DateParamError.TOO_HIGH, "Expiry date lies too far in the future");
	}

	/**
	 * Set consistent state for lecture permissions on writing.
	 */
	public static LecturePermissions handleLecturePermissions(LecturePermissions perms) {
		if (perms == null)
			return new LecturePermissions();
		if (perms.admin && !perms.edit) {
			perms = new LecturePermissions(perms);
			perms.edit = true;
		}
		return perms;
	}

	/**
	 * Set consistent state for image permissions on writing.
	 */
	public static ImagePermissions handleImagePermissions(ImagePermissions perms) {
		if (perms == null)
			return new ImagePermissions();
		if (perms.admin && (!perms.edit || !perms.download || !perms.link)) {
			perms = new ImagePermissions(perms);
			perms.edit = true;
			perms.download = true;
			perms.link = true;
		} else if (perms.edit && !perms.download) {
			perms = new ImagePermissions(perms);
			perms.download = true;
		}
		return perms;
	}

}
