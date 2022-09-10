package org.openslx.bwlp.sat.database.mappers;

import java.sql.ResultSet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.bwlp.sat.database.Database;
import org.openslx.bwlp.sat.database.MysqlConnection;
import org.openslx.bwlp.sat.database.MysqlStatement;

public class DbHelper {

	private static final Logger LOGGER = LogManager.getLogger(DbHelper.class);

	public static boolean isDockerContainerAvailable(){

		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement(
					"SELECT *FROM virtualizer WHERE virtid = \"docker\"");
			ResultSet rs = stmt.executeQuery();

			if (!rs.isBeforeFirst()) {
				// no data, do not enable.
				return false;
			}
			return true;
		} catch (Exception e) {
			LOGGER.error("Query failed in DbHelper.isDockerContainerAvailable()", e);
			return false;
		}
	}
}
