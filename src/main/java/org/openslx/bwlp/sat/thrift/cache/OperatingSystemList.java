package org.openslx.bwlp.sat.thrift.cache;

import java.sql.SQLException;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TTransportException;
import org.openslx.bwlp.sat.database.mappers.DbOsVirt;
import org.openslx.bwlp.thrift.iface.OperatingSystem;
import org.openslx.thrifthelper.ThriftManager;
import org.openslx.util.QuickTimer;
import org.openslx.util.QuickTimer.Task;

/**
 * Holds the list of all known organizations. The list is synchronized with
 * the master server.
 */
public class OperatingSystemList extends CacheBase<List<OperatingSystem>> {

	private static final Logger LOGGER = LogManager.getLogger(OperatingSystemList.class);

	private static final OperatingSystemList instance = new OperatingSystemList();

	public static List<OperatingSystem> get() {
		return instance.getInternal();
	}

	@Override
	protected List<OperatingSystem> getCallback() {
		final List<OperatingSystem> list;
		try {
			list = ThriftManager.getMasterClient().getOperatingSystems();
		} catch (TException e1) {
			LOGGER.warn("Could not fetch OS list from master, using local data...",
					e1 instanceof TTransportException ? null : e1);
			try {
				return DbOsVirt.getOsList();
			} catch (SQLException e) {
				LOGGER.warn("Using local OS list from database also failed.", e);
			}
			return null;
		}
		QuickTimer.scheduleOnce(new Task() {
			@Override
			public void fire() {
				try {
					DbOsVirt.storeOsList(list);
				} catch (SQLException e) {
				}
			}
		});
		return list;
	}

}
