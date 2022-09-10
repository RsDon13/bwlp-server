package org.openslx.bwlp.sat.thrift;

import java.sql.SQLException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.bwlp.sat.database.mappers.DbUser;
import org.openslx.bwlp.sat.permissions.User;
import org.openslx.bwlp.sat.util.Formatter;
import org.openslx.bwlp.thrift.iface.AuthorizationError;
import org.openslx.bwlp.thrift.iface.Role;
import org.openslx.bwlp.thrift.iface.TAuthorizationException;
import org.openslx.bwlp.thrift.iface.TInvalidTokenException;
import org.openslx.bwlp.thrift.iface.TInvocationException;
import org.openslx.bwlp.thrift.iface.UserInfo;
import org.openslx.thrifthelper.ThriftManager;
import org.openslx.util.QuickTimer;
import org.openslx.util.QuickTimer.Task;

/**
 * Manages user sessions. Mainly used to map tokens to users.
 * 
 */
public class SessionManager {

	private static final Logger LOGGER = LogManager.getLogger(SessionManager.class);

	private static class Entry {
		private static final long SESSION_TIMEOUT = TimeUnit.DAYS.toMillis(1);
		private final UserInfo user;
		private long validUntil;

		private Entry(UserInfo user) {
			this.user = user;
			this.validUntil = System.currentTimeMillis() + SESSION_TIMEOUT;
		}

		public void touch(long now) {
			this.validUntil = now + SESSION_TIMEOUT;
		}

		public boolean isTooOld(long now) {
			return validUntil < now;
		}
	}

	// saves the current tokens and the mapped userdata, returning from the server
	private static final Map<String, Entry> tokenManager = new ConcurrentHashMap<>();

	static {
		// Clean cached session periodically
		QuickTimer.scheduleAtFixedDelay(new Task() {
			@Override
			public void fire() {
				final long now = System.currentTimeMillis();
				for (Iterator<Entry> it = tokenManager.values().iterator(); it.hasNext();) {
					Entry e = it.next();
					if (e == null || e.isTooOld(now))
						it.remove();
				}
			}
		}, 60000, 1200600);
	}

	/**
	 * Get the user corresponding to the given token.
	 * 
	 * @param token user's token
	 * @return UserInfo for the matching user
	 * @throws TAuthorizationException if the token is not known or the session
	 *             expired
	 * @throws TInvocationException
	 */
	public static UserInfo getOrFail(String token) throws TAuthorizationException, TInvocationException {
		UserInfo ui = getInternal(token);
		if (ui != null)
			return ui;
		throw new TAuthorizationException(AuthorizationError.NOT_AUTHENTICATED,
				"Your session token is not known to the server");
	}

	/**
	 * Do nothing if a user belongs to the given token and is authorized to use
	 * this satellite, throw an exception otherwise.
	 * 
	 * @param token Token in question
	 * @throws TAuthorizationException
	 * @throws TInvocationException
	 */
	public static void ensureAuthenticated(String token) throws TAuthorizationException, TInvocationException {
		getInternal(token);
	}

	/**
	 * Get the user corresponding to the given token. Returns <code>null</code>
	 * if the token is not known, or the session already timed out.
	 * 
	 * @param token user's token
	 * @return UserInfo for the matching user
	 */
	public static UserInfo get(String token) {
		try {
			return getInternal(token);
		} catch (TAuthorizationException | TInvocationException e) {
			return null;
		}
	}

	private static UserInfo getInternal(String token) throws TAuthorizationException, TInvocationException {
		Entry e = tokenManager.get(token);
		if (e == null) {
			LOGGER.info("Cache miss for token " + token + ", asking master");
			return getRemote(token);
		}
		// User session already cached
		final long now = System.currentTimeMillis();
		if (e.isTooOld(now)) {
			tokenManager.remove(token);
			return getRemote(token);
		}
		e.touch(now);
		return e.user;
	}

	/**
	 * Remove session matching the given token
	 * 
	 * @param token
	 */
	public static void remove(String token) {
		tokenManager.remove(token);
	}

	/**
	 * Get {@link UserInfo} from master server.
	 * 
	 * @param token token of user to get
	 * @return
	 * @throws TAuthorizationException if user is not allowed to use this
	 *             satellite, this exception contains the reason
	 * @throws TInvocationException if something unexpected fails
	 */
	private static UserInfo getRemote(String token) throws TAuthorizationException, TInvocationException {
		UserInfo ui = null;
		try {
			ui = ThriftManager.getMasterClient().getUserFromToken(token);
		} catch (TInvalidTokenException ite) {
			LOGGER.warn("Master says: Invalid token: " + token);
			throw new TAuthorizationException(AuthorizationError.INVALID_TOKEN,
					"Your token is not known to the master server");
		} catch (Exception e) {
			LOGGER.warn("Could not reach master server to query for user token (" + token + ") of a client!",
					e);
			throw new TInvocationException();
		}
		LOGGER.info("Got '" + Formatter.userFullName(ui) + "' (" + ui.userId + "/" + ui.role + ") for token " + token);
		if (ui.role == null) {
			// Fail-safe: No role supplied, assume student
			ui.role = Role.STUDENT;
		}
		// Valid reply, check if user is allowed to communicate with this satellite server
		AuthorizationError authError = User.canLogin(ui);
		handleAuthorizationError(ui, authError);
		// Is valid, insert/update db record, but ignore students
		if (ui.role != Role.STUDENT) {
			try {
				DbUser.writeUserOnLogin(ui);
			} catch (SQLException e) {
				LOGGER.info("User " + ui.userId + " cannot be written to DB - rejecting.");
				throw new TInvocationException();
			}
			// Check again, as it might be a fresh entry to the DB, and we don't allow logins by default
			authError = User.canLogin(ui);
			handleAuthorizationError(ui, authError);
		}
		tokenManager.put(token, new Entry(ui));
		return ui;
	}
	
	private static void handleAuthorizationError(UserInfo ui, AuthorizationError authError) throws TAuthorizationException {
		if (authError == null)
			return;
		
		LOGGER.info("User " + ui.userId + " cannot login: " + authError.toString());
		switch (authError) {
		case ACCOUNT_SUSPENDED:
			throw new TAuthorizationException(authError,
					"Your account is not allowed to log in to this satellite");
		case BANNED_NETWORK:
			throw new TAuthorizationException(authError, "Your IP address is banned from this satellite");
		case INVALID_CREDENTIALS:
		case INVALID_KEY:
		case CHALLENGE_FAILED:
			throw new TAuthorizationException(authError, "Authentication error");
		case INVALID_ORGANIZATION:
			throw new TAuthorizationException(authError,
					"Your organization is not known to this satellite");
		case ORGANIZATION_SUSPENDED:
			throw new TAuthorizationException(authError,
					"Your organization is not allowed to log in to this satellite");
		case NOT_AUTHENTICATED:
		case NO_PERMISSION:
			throw new TAuthorizationException(authError, "No permission");
		case GENERIC_ERROR:
		case INVALID_TOKEN:
		default:
			throw new TAuthorizationException(authError, "Internal server error");
		}
	}

}
