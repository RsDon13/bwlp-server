package org.openslx.bwlp.sat.database.models;

import org.openslx.bwlp.sat.database.mappers.DbImage.DeleteState;

public class LocalImageVersion {

	public final String imageVersionId;

	public final String imageBaseId;

	public final String filePath;

	public final long fileSize;

	public final boolean isValid;

	public final String uploaderId;

	public final long createTime;

	public final long expireTime;

	public final DeleteState deleteState;

	public LocalImageVersion(String imageVersionId, String imageBaseId, String filePath, long fileSize,
			String uploaderId, long createTime, long expireTime, boolean isValid, String deleteState) {
		this.imageVersionId = imageVersionId;
		this.imageBaseId = imageBaseId;
		this.filePath = filePath;
		this.fileSize = fileSize;
		this.uploaderId = uploaderId;
		this.createTime = createTime;
		this.expireTime = expireTime;
		this.isValid = isValid;
		DeleteState ds;
		try {
			ds = DeleteState.valueOf(deleteState);
		} catch (Exception e) {
			ds = DeleteState.KEEP;
		}
		this.deleteState = ds;
	}

	@Override
	public boolean equals(Object that) {
		return this.imageVersionId != null && that != null && (that instanceof LocalImageVersion)
				&& this.imageVersionId.equals(((LocalImageVersion) that).imageVersionId);
	}

	@Override
	public int hashCode() {
		return imageVersionId == null ? 0 : imageVersionId.hashCode() ^ 12345;
	}

	@Override
	public String toString() {
		return "[" + imageVersionId + ": Base=" + imageBaseId + ", filePath=" + filePath + ", fileSize="
				+ fileSize + ", isValid=" + isValid + ", createTime=" + createTime + ", expireTime="
				+ expireTime + ", deleteState=" + deleteState + "]";
	}

}
