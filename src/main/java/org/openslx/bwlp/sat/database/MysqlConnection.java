package org.openslx.bwlp.sat.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MysqlConnection implements AutoCloseable {

	private static final Logger LOGGER = LogManager.getLogger(MysqlConnection.class);

	private static final int CONNECTION_TIMEOUT_MS = 5 * 60 * 1000;

	private final long deadline = System.currentTimeMillis() + CONNECTION_TIMEOUT_MS;

	private final Connection rawConnection;

	private boolean hasPendingQueries = false;

	private List<MysqlStatement> openStatements = new ArrayList<>();

	MysqlConnection(Connection rawConnection) {
		this.rawConnection = rawConnection;
	}

	public MysqlStatement prepareStatement(String sql) throws SQLException {
		if (!sql.startsWith("SELECT") && !sql.startsWith("DESCRIBE") && !sql.startsWith("SHOW")) {
			hasPendingQueries = true;
		}
		MysqlStatement statement = new MysqlStatement(rawConnection, sql);
		openStatements.add(statement);
		return statement;
	}

	public void commit() throws SQLException {
		rawConnection.commit();
		hasPendingQueries = false;
	}

	public void rollback() throws SQLException {
		rawConnection.rollback();
		hasPendingQueries = false;
	}

	boolean isValid() {
		return System.currentTimeMillis() < deadline;
	}

	@Override
	public void close() {
		if (hasPendingQueries) {
			LOGGER.warn("Mysql connection had uncommited queries on .close()", new RuntimeException());
			hasPendingQueries = false;
		}
		try {
			rawConnection.rollback();
		} catch (SQLException e) {
			LOGGER.warn("Rolling back uncommited queries failed!", e);
		}
		if (!openStatements.isEmpty()) {
			for (MysqlStatement statement : openStatements) {
				statement.close();
			}
			openStatements.clear();
		}
		Database.returnConnection(this);
	}

	void release() {
		try {
			rawConnection.close();
		} catch (SQLException e) {
			// Nothing meaningful to do
		}
	}

}
