package org.openslx.bwlp.sat.database.models;

import java.nio.ByteBuffer;
import java.util.List;

public class ImageVersionMeta {

	public final String imageVersionId;
	public final String imageBaseId;
	public final byte[] machineDescription;
	public final List<ByteBuffer> sha1sums;

	public ImageVersionMeta(String imageVersionId, String imageBaseId, byte[] machineDescription,
			List<ByteBuffer> sha1sums) {
		this.imageVersionId = imageVersionId;
		this.imageBaseId = imageBaseId;
		this.machineDescription = machineDescription;
		this.sha1sums = sha1sums;
	}

}
