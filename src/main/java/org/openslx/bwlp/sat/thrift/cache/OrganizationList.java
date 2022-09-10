package org.openslx.bwlp.sat.thrift.cache;

import java.sql.SQLException;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TTransportException;
import org.openslx.bwlp.sat.database.mappers.DbOrganization;
import org.openslx.bwlp.thrift.iface.Organization;
import org.openslx.thrifthelper.ThriftManager;
import org.openslx.util.QuickTimer;
import org.openslx.util.QuickTimer.Task;

/**
 * Holds the list of all known organizations. The list is synchronized with
 * the master server.
 */
public class OrganizationList extends CacheBase<List<Organization>> {

	private static final Logger LOGGER = LogManager.getLogger(OrganizationList.class);

	private static final OrganizationList instance = new OrganizationList();

	public static List<Organization> get() {
		return instance.getInternal();
	}

	@Override
	protected List<Organization> getCallback() throws TException {
		final List<Organization> organizations;
		try {
			organizations = ThriftManager.getMasterClient().getOrganizations();
		} catch (TException e1) {
			LOGGER.warn("Could not fetch Organization list from master, using local data...",
					e1 instanceof TTransportException ? null : e1);
			try {
				return DbOrganization.getAll();
			} catch (SQLException e) {
				LOGGER.warn("Using local Organization list from database also failed.", e);
			}
			return null;
		}
		// Also store the list in the local data base (asynchronous, in the timer thread)
		QuickTimer.scheduleOnce(new Task() {
			@Override
			public void fire() {
				try {
					DbOrganization.storeOrganizations(organizations);
				} catch (SQLException e) {
				}
			}
		});
		return organizations;
	}

	public static Organization find(String organizationId) {
		List<Organization> list = get();
		if (list == null)
			return null;
		for (Organization org : list) {
			if (org != null && organizationId.equals(org.organizationId))
				return org;
		}
		return null;
	}

}
