package org.openslx.bwlp.sat.database;

import java.io.Closeable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Class for creating {@link PreparedStatement}s with named parameters. Based on
 * <a href=
 * "http://www.javaworld.com/article/2077706/core-java/named-parameters-for-preparedstatement.html?page=2"
 * >Named Parameters for PreparedStatement</a>
 */
public class MysqlStatement implements Closeable {

	private static final QueryCache cache = new QueryCache();

	private final PreparsedQuery query;

	private final PreparedStatement statement;

	private final List<ResultSet> openResultSets = new ArrayList<>();

	MysqlStatement(Connection con, String sql) throws SQLException {
		PreparsedQuery query;
		synchronized (cache) {
			query = cache.get(sql);
		}
		if (query == null) {
			query = parse(sql);
			synchronized (cache) {
				cache.put(sql, query);
			}
		}
		this.query = query;
		this.statement = con.prepareStatement(query.sql);
	}

	/**
	 * Returns the indexes for a parameter.
	 * 
	 * @param name parameter name
	 * @return parameter indexes
	 * @throws IllegalArgumentException if the parameter does not exist
	 */
	private List<Integer> getIndexes(String name) {
		List<Integer> indexes = query.indexMap.get(name);
		if (indexes == null) {
			throw new IllegalArgumentException("Parameter not found: " + name);
		}
		return indexes;
	}

	/**
	 * Sets a parameter.
	 * 
	 * @param name parameter name
	 * @param value parameter value
	 * @throws SQLException if an error occurred
	 * @throws IllegalArgumentException if the parameter does not exist
	 * @see PreparedStatement#setObject(int, java.lang.Object)
	 */
	public void setObject(String name, Object value) throws SQLException {
		List<Integer> indexes = getIndexes(name);
		for (Integer index : indexes) {
			statement.setObject(index, value);
		}
	}

	/**
	 * Sets a parameter.
	 * 
	 * @param name parameter name
	 * @param value parameter value
	 * @throws SQLException if an error occurred
	 * @throws IllegalArgumentException if the parameter does not exist
	 * @see PreparedStatement#setString(int, java.lang.String)
	 */
	public void setString(String name, String value) throws SQLException {
		List<Integer> indexes = getIndexes(name);
		for (Integer index : indexes) {
			statement.setString(index, value);
		}
	}

	/**
	 * Sets a parameter.
	 * 
	 * @param name parameter name
	 * @param value parameter value
	 * @throws SQLException if an error occurred
	 * @throws IllegalArgumentException if the parameter does not exist
	 * @see PreparedStatement#setInt(int, int)
	 */
	public void setInt(String name, int value) throws SQLException {
		List<Integer> indexes = getIndexes(name);
		for (Integer index : indexes) {
			statement.setInt(index, value);
		}
	}

	/**
	 * Sets a parameter.
	 * 
	 * @param name parameter name
	 * @param value parameter value
	 * @throws SQLException if an error occurred
	 * @throws IllegalArgumentException if the parameter does not exist
	 * @see PreparedStatement#setLong(int, long)
	 */
	public void setLong(String name, long value) throws SQLException {
		List<Integer> indexes = getIndexes(name);
		for (Integer index : indexes) {
			statement.setLong(index, value);
		}
	}

	/**
	 * Sets a parameter.
	 * 
	 * @param name parameter name
	 * @param value parameter value
	 * @throws SQLException if an error occurred
	 * @throws IllegalArgumentException if the parameter does not exist
	 * @see PreparedStatement#setBoolean(int, boolean)
	 */
	public void setBoolean(String name, boolean value) throws SQLException {
		List<Integer> indexes = getIndexes(name);
		for (Integer index : indexes) {
			statement.setBoolean(index, value);
		}
	}

	/**
	 * Sets a parameter.
	 * 
	 * @param name parameter name
	 * @param value parameter value
	 * @throws SQLException if an error occurred
	 * @throws IllegalArgumentException if the parameter does not exist
	 * @see PreparedStatement#setBoolean(int, boolean)
	 */
	public void setBinary(String name, byte[] value) throws SQLException {
		List<Integer> indexes = getIndexes(name);
		for (Integer index : indexes) {
			statement.setBytes(index, value);
		}
	}

	/**
	 * Executes the statement.
	 * 
	 * @return true if the first result is a {@link ResultSet}
	 * @throws SQLException if an error occurred
	 * @see PreparedStatement#execute()
	 */
	public boolean execute() throws SQLException {
		return statement.execute();
	}

	/**
	 * Executes the statement, which must be a query.
	 * 
	 * @return the query results
	 * @throws SQLException if an error occurred
	 * @see PreparedStatement#executeQuery()
	 */
	public ResultSet executeQuery() throws SQLException {
		ResultSet rs = statement.executeQuery();
		openResultSets.add(rs);
		return rs;
	}

	/**
	 * Executes the statement, which must be an SQL INSERT, UPDATE or DELETE
	 * statement; or an SQL statement that returns nothing, such as a DDL
	 * statement.
	 * 
	 * @return number of rows affected
	 * @throws SQLException if an error occurred
	 * @see PreparedStatement#executeUpdate()
	 */
	public int executeUpdate() throws SQLException {
		return statement.executeUpdate();
	}

	/**
	 * Closes the statement.
	 * 
	 * @see Statement#close()
	 */
	@Override
	public void close() {
		for (ResultSet rs : openResultSets) {
			try {
				rs.close();
			} catch (SQLException e) {
				//
			}
		}
		try {
			statement.close();
		} catch (SQLException e) {
			// Nothing to do
		}
	}

	/**
	 * Adds the current set of parameters as a batch entry.
	 * 
	 * @throws SQLException if something went wrong
	 */
	public void addBatch() throws SQLException {
		statement.addBatch();
	}

	/**
	 * Executes all of the batched statements.
	 * 
	 * See {@link Statement#executeBatch()} for details.
	 * 
	 * @return update counts for each statement
	 * @throws SQLException if something went wrong
	 */
	public int[] executeBatch() throws SQLException {
		return statement.executeBatch();
	}

	// static methods

	private static PreparsedQuery parse(String query) {
		int length = query.length();
		StringBuffer parsedQuery = new StringBuffer(length);
		Map<String, List<Integer>> paramMap = new HashMap<>();
		boolean inSingleQuote = false;
		boolean inDoubleQuote = false;
		boolean hasBackslash = false;
		int index = 1;

		for (int i = 0; i < length; i++) {
			char c = query.charAt(i);
			if (hasBackslash) {
				// Last char was a backslash, so we ignore the current char
				hasBackslash = false;
			} else if (c == '\\') {
				// This is a backslash, next char will be escaped
				hasBackslash = true;
			} else if (inSingleQuote) {
				// End of quoted string
				if (c == '\'') {
					inSingleQuote = false;
				}
			} else if (inDoubleQuote) {
				// End of quoted string
				if (c == '"') {
					inDoubleQuote = false;
				}
			} else {
				// Not in string, look for named params
				if (c == '\'') {
					inSingleQuote = true;
				} else if (c == '"') {
					inDoubleQuote = true;
				} else if (c == ':' && i + 1 < length && Character.isJavaIdentifierStart(query.charAt(i + 1))) {
					int j = i + 2;
					while (j < length && Character.isJavaIdentifierPart(query.charAt(j))) {
						j++;
					}
					String name = query.substring(i + 1, j);
					c = '?'; // replace the parameter with a question mark
					i += name.length(); // skip past the end of the parameter

					List<Integer> indexList = paramMap.get(name);
					if (indexList == null) {
						indexList = new ArrayList<>();
						paramMap.put(name, indexList);
					}
					indexList.add(Integer.valueOf(index));

					index++;
				}
			}
			parsedQuery.append(c);
		}

		return new PreparsedQuery(parsedQuery.toString(), paramMap);
	}

	// private helper classes

	private static class PreparsedQuery {
		private final Map<String, List<Integer>> indexMap;
		private final String sql;

		public PreparsedQuery(String sql, Map<String, List<Integer>> indexMap) {
			this.sql = sql;
			this.indexMap = indexMap;
		}
	}

	private static class QueryCache extends LinkedHashMap<String, PreparsedQuery> {
		private static final long serialVersionUID = 1L;

		public QueryCache() {
			super(30, (float) 0.75, true);
		}

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, PreparsedQuery> eldest) {
			return size() > 40;
		}
	}

}
