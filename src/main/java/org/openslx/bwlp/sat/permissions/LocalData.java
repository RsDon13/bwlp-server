package org.openslx.bwlp.sat.permissions;

import java.sql.SQLException;
import java.util.Map;

import org.openslx.bwlp.sat.database.mappers.DbOrganization;
import org.openslx.bwlp.sat.database.mappers.DbUser;
import org.openslx.bwlp.sat.database.models.LocalOrganization;
import org.openslx.bwlp.sat.database.models.LocalUser;
import org.openslx.bwlp.thrift.iface.UserInfo;
import org.openslx.util.TimeoutHashMap;

public class LocalData {

	/**
	 * Cache local user data, might be called quite often.
	 */
	private static final Map<String, LocalUser> localUserCache = new TimeoutHashMap<>(15000);

	protected static LocalUser getLocalUser(UserInfo user) {
		synchronized (localUserCache) {
			LocalUser local = localUserCache.get(user.userId);
			if (local != null)
				return local;
		}
		LocalUser localData;
		try {
			localData = DbUser.getLocalData(user);
		} catch (SQLException e) {
			return null;
		}
		if (localData == null)
			return null;
		synchronized (localUserCache) {
			localUserCache.put(user.userId, localData);
		}
		return localData;
	}

	/**
	 * Cache local organization data, might be called quite often.
	 */
	private static final Map<String, LocalOrganization> localOrganizationCache = new TimeoutHashMap<>(15000);

	protected static LocalOrganization getLocalOrganization(String organizationId) {
		synchronized (localOrganizationCache) {
			LocalOrganization local = localOrganizationCache.get(organizationId);
			if (local != null)
				return local;
		}
		LocalOrganization localData;
		try {
			localData = DbOrganization.getLocalData(organizationId);
		} catch (SQLException e) {
			return null;
		}
		if (localData == null)
			return null;
		synchronized (localOrganizationCache) {
			localOrganizationCache.put(organizationId, localData);
		}
		return localData;
	}

}
