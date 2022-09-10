package org.openslx.bwlp.sat.thrift.cache;

import java.sql.SQLException;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TTransportException;
import org.openslx.bwlp.sat.database.mappers.DbOsVirt;
import org.openslx.bwlp.thrift.iface.Virtualizer;
import org.openslx.thrifthelper.ThriftManager;
import org.openslx.util.QuickTimer;
import org.openslx.util.QuickTimer.Task;

/**
 * Holds the list of all known virtualizers. The list is synchronized with
 * the master server.
 */
public class VirtualizerList extends CacheBase<List<Virtualizer>> {

	private static final Logger LOGGER = LogManager.getLogger(VirtualizerList.class);

	private static final VirtualizerList instance = new VirtualizerList();

	public static List<Virtualizer> get() {
		return instance.getInternal();
	}

	@Override
	protected List<Virtualizer> getCallback() throws TException {
		final List<Virtualizer> list;
		try {
			list = ThriftManager.getMasterClient().getVirtualizers();
		} catch (TException e1) {
			LOGGER.warn("Could not fetch Virtualizer list from master, using local data...",
					e1 instanceof TTransportException ? null : e1);
			try {
				return DbOsVirt.getVirtualizerList();
			} catch (SQLException e) {
				LOGGER.warn("Using local Virtualizer list from database also failed.", e);
			}
			return null;
		}
		QuickTimer.scheduleOnce(new Task() {
			@Override
			public void fire() {
				try {
					DbOsVirt.storeVirtualizerList(list);
				} catch (SQLException e) {
				}
			}
		});
		return list;
	}

}
