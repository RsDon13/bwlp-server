package org.openslx.bwlp.sat.database.models;

public class LocalUser {

	public final long lastLogin;
	public final boolean canLogin;
	public final boolean isSuperUser;
	public final boolean emailNotifications;

	public LocalUser(long lastLogin, boolean canLogin, boolean isSuperUser, boolean emailNotifications) {
		this.lastLogin = lastLogin;
		this.canLogin = canLogin;
		this.isSuperUser = isSuperUser;
		this.emailNotifications = emailNotifications;
	}

}
