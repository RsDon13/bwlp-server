package org.openslx.bwlp.sat;

import java.sql.SQLException;

import org.openslx.bwlp.sat.database.Paginator;
import org.openslx.bwlp.sat.database.mappers.DbConfiguration;
import org.openslx.bwlp.sat.util.Constants;
import org.openslx.bwlp.thrift.iface.ImagePermissions;
import org.openslx.bwlp.thrift.iface.LecturePermissions;
import org.openslx.bwlp.thrift.iface.SatelliteConfig;
import org.openslx.bwlp.thrift.iface.SscMode;
import org.openslx.util.GenericDataCache;

public class RuntimeConfig {

	private static GenericDataCache<SatelliteConfig> cache = new GenericDataCache<SatelliteConfig>(60000) {

		@Override
		protected SatelliteConfig update() {
			SatelliteConfig satConfig = null, readConfig = null;
			try {
				readConfig = DbConfiguration.getSatelliteConfig();
			} catch (SQLException e) {
				// Fall through
			}
			if (readConfig != null) {
				satConfig = readConfig.deepCopy();
			}
			if (satConfig == null) {
				satConfig = new SatelliteConfig();
				satConfig.maxLocationsPerLecture = -1;
			}
			if (satConfig.defaultImagePermissions == null) {
				satConfig.setDefaultImagePermissions(new ImagePermissions(true, true, false, false));
			}
			if (satConfig.defaultLecturePermissions == null) {
				satConfig.setDefaultLecturePermissions(new LecturePermissions(false, false));
			}
			if (satConfig.maxImageValidityDays == 0) {
				satConfig.setMaxImageValidityDays(220);
			} else if (satConfig.maxImageValidityDays < 7) {
				satConfig.maxImageValidityDays = 7;
			}
			if (satConfig.maxLectureValidityDays == 0) {
				satConfig.setMaxLectureValidityDays(220);
			} else if (satConfig.maxLectureValidityDays < 7) {
				satConfig.setMaxLectureValidityDays(7);
			}
			if (satConfig.maxTransfers == 0) {
				satConfig.setMaxTransfers(Constants.MAX_UPLOADS_PER_USER);
			}
			if (!satConfig.isSetAllowLoginByDefault()) {
				satConfig.setAllowLoginByDefault(true);
			}
			satConfig.setPageSize(Paginator.PER_PAGE);
			satConfig.setMaxConnectionsPerTransfer(Constants.MAX_CONNECTIONS_PER_TRANSFER);
			if (satConfig.maxLocationsPerLecture == -1) {
				satConfig.setMaxLocationsPerLecture(4);
			}
			if (satConfig.serverSideCopy == null) {
				satConfig.serverSideCopy = SscMode.AUTO;
			}
			// Update if we sanitized or added anything
			if (!satConfig.equals(readConfig)) {
				try {
					DbConfiguration.setSatelliteConfig(satConfig);
				} catch (SQLException e) {
				}
			}
			return satConfig;
		}
	};

	public static SatelliteConfig get() {
		return cache.get().deepCopy();
	}

	public static long getMaxImageValiditySeconds() {
		return cache.get().getMaxImageValidityDays() * 86400l;
	}

	public static long getMaxLectureValiditySeconds() {
		return cache.get().getMaxLectureValidityDays() * 86400l;
	}

	public static int getMaxLocationsPerLecture() {
		return cache.get().getMaxLocationsPerLecture();
	}

	/**
	 * How long a version that is not the latest version of an image will be
	 * kept.
	 * 
	 * @return maximum lifetime in seconds
	 */
	public static long getOldVersionExpireSeconds() {
		return 8 * 86400;
	}

	public static boolean allowLoginByDefault() {
		return cache.get().allowLoginByDefault;
	}
	
	public static boolean allowStudentDownload() {
		return cache.get().allowStudentDownload;
	}
	
}
