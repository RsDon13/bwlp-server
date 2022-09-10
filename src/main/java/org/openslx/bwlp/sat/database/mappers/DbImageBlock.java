package org.openslx.bwlp.sat.database.mappers;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.bwlp.sat.database.Database;
import org.openslx.bwlp.sat.database.MysqlConnection;
import org.openslx.bwlp.sat.database.MysqlStatement;
import org.openslx.bwlp.sat.util.FileSystem;
import org.openslx.filetransfer.FileRange;
import org.openslx.filetransfer.LocalChunkSource.ChunkSource;
import org.openslx.filetransfer.util.ChunkStatus;
import org.openslx.filetransfer.util.FileChunk;

public class DbImageBlock {

	private static final Logger LOGGER = LogManager.getLogger(DbImageBlock.class);

	private static AsyncThread asyncBlockUpdate = null;

	private static synchronized void initAsyncThread() {
		if (asyncBlockUpdate == null) {
			asyncBlockUpdate = new AsyncThread();
			asyncBlockUpdate.start();
		}
	}

	public static void asyncUpdate(String imageVersionId, FileChunk chunk) throws InterruptedException {
		initAsyncThread();
		asyncBlockUpdate.put(new ChunkUpdate(imageVersionId, chunk.range,
				chunk.getStatus() != ChunkStatus.COMPLETE));
	}

	private static class AsyncThread extends Thread {
		private final ArrayBlockingQueue<ChunkUpdate> queue = new ArrayBlockingQueue<>(100);
		
		public AsyncThread() {
			super("DbBlockUpdater");
		}

		public void put(ChunkUpdate chunk) throws InterruptedException {
			queue.put(chunk);
		}

		@Override
		public void run() {
			try {
				while (!interrupted()) {
					ChunkUpdate chunk = queue.take();
					Thread.sleep(100);
					try (MysqlConnection connection = Database.getConnection()) {
						MysqlStatement stmt = connection.prepareStatement("UPDATE imageblock SET ismissing = :ismissing"
								+ " WHERE imageversionid = :imageversionid AND startbyte = :startbyte AND blocksize = :blocksize");
						do {
							stmt.setBoolean("ismissing", chunk.isMissing);
							stmt.setString("imageversionid", chunk.imageVersionId);
							stmt.setLong("startbyte", chunk.range.startOffset);
							stmt.setInt("blocksize", chunk.range.getLength());
							stmt.executeUpdate();
							chunk = queue.poll();
						} while (chunk != null);
						connection.commit();
					} catch (SQLException e) {
						LOGGER.error("Query failed in DbImageBlock.AsyncThread.run()", e);
						continue;
					}
					Thread.sleep(2000);
				}
			} catch (InterruptedException e) {
				LOGGER.debug("async thread interrupted");
				interrupt();
			}
		}
	}

	private static class ChunkUpdate {
		public final String imageVersionId;
		public final FileRange range;
		public final boolean isMissing;

		public ChunkUpdate(String imageVersionId, FileRange range, boolean isMissing) {
			this.imageVersionId = imageVersionId;
			this.range = range;
			this.isMissing = isMissing;
		}
	}

	public static void insertChunkList(String imageVersionId, List<FileChunk> all, boolean missing)
			throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("INSERT IGNORE INTO imageblock"
					+ " (imageversionid, startbyte, blocksize, blocksha1, ismissing) VALUES"
					+ " (:imageversionid, :startbyte, :blocksize, :blocksha1, :ismissing)");
			stmt.setString("imageversionid", imageVersionId);
			stmt.setBoolean("ismissing", missing);
			for (FileChunk chunk : all) {
				stmt.setLong("startbyte", chunk.range.startOffset);
				stmt.setInt("blocksize", chunk.range.getLength());
				stmt.setBinary("blocksha1", chunk.getSha1Sum());
				stmt.executeUpdate();
			}
			connection.commit();
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImageBlock.insertChunkList()", e);
			throw e;
		}
	}

	/**
	 * Get list of block hashes for an image version id. The hashes, as usual,
	 * refer to 16MiB blocks. If hashes are missing, nulls will be inserted into
	 * the list, since otherwise there is no way to reconstruct the offset of
	 * the block in the file. Note however that missing hashes at the end of the
	 * list will not be added as nulls, so there still could be less hashes in
	 * the list than blocks in the file.
	 */
	static List<ByteBuffer> getBlockHashes(MysqlConnection connection, String imageVersionId)
			throws SQLException {
		MysqlStatement stmt = connection.prepareStatement("SELECT startbyte, blocksha1 FROM imageblock"
				+ " WHERE imageversionid = :imageversionid ORDER BY startbyte ASC");
		stmt.setString("imageversionid", imageVersionId);
		ResultSet rs = stmt.executeQuery();
		List<ByteBuffer> list = new ArrayList<>();
		long expectedOffset = 0;
		while (rs.next()) {
			long currentOffset = rs.getLong("startbyte");
			if (currentOffset < expectedOffset)
				continue;
			while (currentOffset > expectedOffset) {
				list.add(null);
				expectedOffset += FileChunk.CHUNK_SIZE;
			}
			if (currentOffset == expectedOffset) {
				list.add(ByteBuffer.wrap(rs.getBytes("blocksha1")));
				expectedOffset += FileChunk.CHUNK_SIZE;
			}
		}
		return list;
	}

	public static List<ByteBuffer> getBlockHashes(String imageVersionId) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			return getBlockHashes(connection, imageVersionId);
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImage.getBlockHashes()", e);
			throw e;
		}
	}

	public static List<Boolean> getMissingStatusList(String imageVersionId) throws SQLException {
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("SELECT startbyte, ismissing FROM imageblock"
					+ " WHERE imageversionid = :imageversionid ORDER BY startbyte ASC");
			stmt.setString("imageversionid", imageVersionId);
			ResultSet rs = stmt.executeQuery();
			List<Boolean> list = new ArrayList<>();
			long expectedOffset = 0;
			while (rs.next()) {
				long currentOffset = rs.getLong("startbyte");
				if (currentOffset < expectedOffset)
					continue;
				while (currentOffset > expectedOffset) {
					list.add(Boolean.TRUE);
					expectedOffset += FileChunk.CHUNK_SIZE;
				}
				if (currentOffset == expectedOffset) {
					list.add(rs.getBoolean("ismissing"));
					expectedOffset += FileChunk.CHUNK_SIZE;
				}
			}
			return list;
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImageBlock.getBlockStatuses()", e);
			throw e;
		}
	}

	public static List<ChunkSource> getBlocksWithHash(List<byte[]> sums) throws SQLException {
		List<ChunkSource> list = null;
		try (MysqlConnection connection = Database.getConnection()) {
			MysqlStatement stmt = connection.prepareStatement("SELECT startbyte, blocksize, filepath FROM imageblock"
					+ " INNER JOIN imageversion USING (imageversionid)"
					+ " WHERE blocksha1 = :sha1 GROUP BY imageversionid");
			for (byte[] sha1 : sums) {
				stmt.setBinary("sha1", sha1);
				ResultSet rs = stmt.executeQuery();
				if (!rs.next())
					continue;
				ChunkSource cs = new ChunkSource(sha1);
				do {
					cs.addFile(FileSystem.composeAbsolutePath(rs.getString("filepath")).getAbsolutePath(),
							rs.getLong("startbyte"), rs.getInt("blocksize"));
				} while (rs.next());
				if (list == null) {
					list = new ArrayList<>();
				}
				list.add(cs);
			}
		} catch (SQLException e) {
			LOGGER.error("Query failed in DbImageBlock.getBlocksWithHash()", e);
			throw e;
		}
		return list;
	}

}
