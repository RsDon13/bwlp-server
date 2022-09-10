package org.openslx.bwlp.sat.database.mappers;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.bwlp.sat.database.Database;
import org.openslx.bwlp.sat.database.MysqlConnection;
import org.openslx.bwlp.sat.database.MysqlStatement;
import org.openslx.bwlp.thrift.iface.OperatingSystem;
import org.openslx.bwlp.thrift.iface.Virtualizer;

public class DbOsVirt {

	private static final Logger LOGGER = LogManager.getLogger(DbOsVirt.class);

	public static void storeOsList(List<OperatingSystem> list) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			{
				MysqlStatement osGeneralStmt = connection.prepareStatement("INSERT INTO operatingsystem"
						+ " (osid, displayname, architecture, maxmem, maxcpu) VALUES"
						+ " (:osid, :displayname, :architecture, :maxmem, :maxcpu)"
						+ " ON DUPLICATE KEY UPDATE displayname = VALUES(displayname), architecture = VALUES(architecture),"
						+ "  maxmem = VALUES(maxmem), maxcpu = VALUES(maxcpu)");
				for (OperatingSystem os : list) {
					osGeneralStmt.setInt("osid", os.osId);
					osGeneralStmt.setString("displayname", os.osName);
					osGeneralStmt.setString("architecture", os.architecture);
					osGeneralStmt.setInt("maxmem", os.maxMemMb);
					osGeneralStmt.setInt("maxcpu", os.maxCores);
					osGeneralStmt.executeUpdate();
				}
				osGeneralStmt.close();
			}
			connection.commit();
			MysqlStatement virtStmt = connection.prepareStatement("INSERT IGNORE INTO os_x_virt"
					+ " (osid, virtid, virtoskeyword)              VALUES"
					+ " (:osid, :virtid, :virtoskeyword)"
					+ " ON DUPLICATE KEY UPDATE virtoskeyword = VALUES(virtoskeyword)");
			for (OperatingSystem os : list) {
				if (os.virtualizerOsId == null) {
					LOGGER.warn("OS " + os.osName + " (" + os.osId + ") has no virtualizerkeys");
				} else {
					virtStmt.setInt("osid", os.osId);
					for (Entry<String, String> virtkey : os.virtualizerOsId.entrySet()) {
						virtStmt.setString("virtid", virtkey.getKey());
						virtStmt.setString("virtoskeyword", virtkey.getValue());
						virtStmt.executeUpdate();
					}
				}
			}
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbOsVirt.storeOsList()", e);
			throw e;
		}
	}

	public static List<OperatingSystem> getOsList() throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			// Query OSs
			MysqlStatement stmt = connection.prepareStatement("SELECT"
					+ " osid, displayname, architecture, maxmem, maxcpu FROM operatingsystem");
			ResultSet rs = stmt.executeQuery();
			List<OperatingSystem> list = new ArrayList<>();
			Map<Integer, Map<String, String>> osVirtMappings = getOsVirtMappings(connection);
			while (rs.next()) {
				int osId = rs.getInt("osid");
				list.add(new OperatingSystem(osId, rs.getString("displayname"), osVirtMappings.get(osId),
						rs.getString("architecture"), rs.getInt("maxmem"), rs.getInt("maxcpu")));
			}
			return list;
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbOsVirt.getOsList()", e);
			throw e;
		}
	}

	private static Map<Integer, Map<String, String>> getOsVirtMappings(MysqlConnection connection)
			throws SQLException {
		MysqlStatement stmt = connection.prepareStatement("SELECT osid, virtid, virtoskeyword FROM os_x_virt");
		ResultSet rs = stmt.executeQuery();
		Map<Integer, Map<String, String>> map = new HashMap<>();
		while (rs.next()) {
			Integer osId = rs.getInt("osid");
			Map<String, String> osMap = map.get(osId);
			if (osMap == null) {
				osMap = new HashMap<>();
				map.put(osId, osMap);
			}
			osMap.put(rs.getString("virtid"), rs.getString("virtoskeyword"));
		}
		return map;
	}

	public static void storeVirtualizerList(List<Virtualizer> list) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("INSERT INTO virtualizer"
					+ " (virtid, virtname) VALUES" + " (:virtid, :virtname)"
					+ " ON DUPLICATE KEY UPDATE virtname = VALUES(virtname)");
			for (Virtualizer virt : list) {
				stmt.setString("virtid", virt.virtId);
				stmt.setString("virtname", virt.virtName);
				stmt.executeUpdate();
			}
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbOsVirt.storeVirtualizerList()", e);
			throw e;
		}
	}

	public static List<Virtualizer> getVirtualizerList() throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("SELECT virtid, virtname" + " FROM virtualizer");
			ResultSet rs = stmt.executeQuery();
			List<Virtualizer> list = new ArrayList<>();
			while (rs.next()) {
				list.add(new Virtualizer(rs.getString("virtid"), rs.getString("virtname")));
			}
			return list;
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbOsVirt.getVirtualizerList()", e);
			throw e;
		}
	}

}
