package org.openslx.bwlp.sat.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.bwlp.sat.util.Configuration;

public class Database {

	private static final Logger LOGGER = LogManager.getLogger(Database.class);
	/**
	 * Pool of available connections.
	 */
	private static final Queue<MysqlConnection> pool = new ConcurrentLinkedQueue<>();

	/**
	 * Set of connections currently handed out.
	 */
	private static final Set<MysqlConnection> busyConnections = Collections.newSetFromMap(new ConcurrentHashMap<MysqlConnection, Boolean>());

	static {
		try {
			// Hack for some Java versions to register and instantiate the MySQL connection driver
			Class.forName("com.mysql.cj.jdbc.Driver").newInstance();
		} catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
			LOGGER.fatal("Cannot get mysql JDBC driver!", e);
			System.exit(1);
		}
	}

	/**
	 * Get a connection to the database. If there is a valid connection in the
	 * pool, it will be returned. Otherwise, a new connection is created. If
	 * there are more than 20 busy connections, <code>null</code> is returned.
	 * 
	 * @return connection to database, or <code>null</code>
	 */
	public static MysqlConnection getConnection() {
		MysqlConnection con;
		for (;;) {
			con = pool.poll();
			if (con == null)
				break;
			if (!con.isValid()) {
				con.release();
				continue;
			}
			if (!busyConnections.add(con))
				throw new RuntimeException("Tried to hand out a busy connection!");
			return con;
		}
		// No pooled connection
		if (busyConnections.size() > 20) {
			LOGGER.warn("Too many open MySQL connections. Possible connection leak!");
			return null;
		}
		try {
			// Create fresh connection
			Connection rawConnection = DriverManager.getConnection(Configuration.getDbUri(),
					Configuration.getDbUsername(), Configuration.getDbPassword());
			// By convention in our program we don't want auto commit
			rawConnection.setAutoCommit(false);
			// Wrap into our proxy
			con = new MysqlConnection(rawConnection);
			// Keep track of busy mysql connection
			if (!busyConnections.add(con))
				throw new RuntimeException("Tried to hand out a busy connection!");
			return con;
		} catch (SQLException e) {
			LOGGER.info("Failed to connect to local mysql server", e);
		}
		return null;
	}

	/**
	 * Called by a {@link MysqlConnection} when its <code>close()</code>-method
	 * is called, so the connection will be added to the pool of available
	 * connections again.
	 * 
	 * @param connection
	 */
	static void returnConnection(MysqlConnection connection) {
		if (!busyConnections.remove(connection))
			throw new RuntimeException("Tried to return a mysql connection to the pool that was not taken!");
		pool.add(connection);
	}

	public static void printCharsetInformation() {
		LOGGER.info("MySQL charset related variables:");
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("SHOW VARIABLES LIKE :what");
			stmt.setString("what", "char%");
			ResultSet rs = stmt.executeQuery();
			while (rs.next()) {
				LOGGER.info(rs.getString("Variable_name") + ": " + rs.getString("Value"));
			}
			stmt.setString("what", "collat%");
			rs = stmt.executeQuery();
			while (rs.next()) {
				LOGGER.info(rs.getString("Variable_name") + ": " + rs.getString("Value"));
			}
		} catch (SQLException e) {
			LOGGER.error("Query failed in Database.printCharsetInformation()", e);
		}
		LOGGER.info("End of variables");
	}

	public static void printDebug() {
		LOGGER.info("Available: " + pool.size());
		LOGGER.info("Busy: " + busyConnections.size());
	}

}// end class
