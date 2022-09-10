package org.openslx.bwlp.sat.database.mappers;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.bwlp.sat.database.Database;
import org.openslx.bwlp.sat.database.MysqlConnection;
import org.openslx.bwlp.sat.database.MysqlStatement;
import org.openslx.bwlp.sat.database.models.LocalOrganization;
import org.openslx.bwlp.thrift.iface.Organization;

public class DbOrganization {

	private static final Logger LOGGER = LogManager.getLogger(DbOrganization.class);

	/**
	 * Store the given list of organizations (coming from the master server) to
	 * the database, or update the meta data of the organizations, if already
	 * existent.
	 * 
	 * @param organizations
	 * @return
	 * @throws SQLException
	 */
	public static boolean storeOrganizations(List<Organization> organizations) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("INSERT INTO organization"
					+ " (organizationid, displayname, canlogin) VALUES (:id, :name, 0)"
					+ " ON DUPLICATE KEY UPDATE displayname = VALUES(displayname)");
			for (Organization organization : organizations) {
				stmt.setString("id", organization.organizationId);
				stmt.setString("name", organization.displayName);
				stmt.executeUpdate();
			}
			connection.commit();
			return true;
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbOrganization.storeOrganization()", e);
			throw e;
		}
	}

	/**
	 * Get local-only data for the given organization. Local-only data is not
	 * supplied by the master server and might differ between satellites.
	 * 
	 * @param organizationId
	 * @return
	 * @throws SQLException
	 */
	public static LocalOrganization getLocalData(String organizationId) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("SELECT canlogin FROM organization"
					+ " WHERE organizationid = :organizationid");
			stmt.setString("organizationid", organizationId);
			ResultSet rs = stmt.executeQuery();
			if (!rs.next())
				return null;
			return new LocalOrganization(rs.getBoolean("canlogin"));
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbOrganization.getLocalData()", e);
			throw e;
		}
	}

	/**
	 * Get list of organizations where users are allowed to login to the local
	 * satellite.
	 * 
	 * @return
	 * @throws SQLException
	 */
	public static List<Organization> getLoginAllowedOrganizations() throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("SELECT organizationid, displayname"
					+ " FROM organization WHERE canlogin = 1");
			ResultSet rs = stmt.executeQuery();
			List<Organization> list = new ArrayList<>();
			while (rs.next()) {
				list.add(new Organization(rs.getString("organizationid"), rs.getString("displayname"), null,
						null));
			}
			return list;
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbOrganization.getLoginAllowedOrganizations()", e);
			throw e;
		}
	}

	/**
	 * Change the "canlogin" flag of the given organization.
	 * 
	 * @param organizationId
	 * @param canlogin
	 * @throws SQLException
	 */
	public static void setCanLogin(String organizationId, boolean canlogin) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("UPDATE organization"
					+ " SET canlogin = :canlogin WHERE organizationid = :organizationid");
			stmt.setString("organizationid", organizationId);
			stmt.setBoolean("canlogin", canlogin);
			stmt.executeUpdate();
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbOrganization.setCanLogin()", e);
			throw e;
		}
	}

	/**
	 * Return list of known organizations. This is a backup solution for
	 * fetching the list form them aster server, as this one doesn't fill all
	 * fields.
	 * 
	 * @return list of all known organizations
	 * @throws SQLException
	 */
	public static List<Organization> getAll() throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("SELECT" + " o.organizationid, o.displayname"
					+ " FROM organization o");
			ResultSet rsOrg = stmt.executeQuery();
			List<Organization> list = new ArrayList<>();
			while (rsOrg.next()) {
				list.add(new Organization(rsOrg.getString("organizationid"), rsOrg.getString("displayname"),
						null, null));
			}
			return list;
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbOrganization.getAll()", e);
			throw e;
		}
	}

}
