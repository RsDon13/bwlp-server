package org.openslx.bwlp.sat.fileserv;

import java.sql.SQLException;
import java.util.List;

import org.openslx.bwlp.sat.database.mappers.DbImageBlock;
import org.openslx.filetransfer.LocalChunkSource;

public class StorageChunkSource implements LocalChunkSource {
	
	public static final StorageChunkSource instance = new StorageChunkSource();
	
	@Override
	public List<ChunkSource> getCloneSources(List<byte[]> sums) {
		try {
			return DbImageBlock.getBlocksWithHash(sums);
		} catch (SQLException e) {
		}
		return null;
	}

}
