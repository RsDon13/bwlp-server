package org.openslx.bwlp.sat.thrift.cache;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.thrift.TException;

/**
 * Class that caches an instance of a given class for 10 minutes.
 * If the cache expired and a fresh instance cannot be acquired,
 * the old instance will be returned.
 * 
 * @param <T> The class to cache
 */
public abstract class CacheBase<T> {

	private static final Logger LOGGER = LogManager.getLogger(CacheBase.class);

	private static final int TIMEOUT = 10 * 60 * 1000;

	private T cachedInstance = null;

	private long cacheTimeout = 0;

	protected abstract T getCallback() throws TException;

	protected synchronized T getInternal() {
		final long now = System.currentTimeMillis();
		if (cachedInstance == null || now > cacheTimeout) {
			try {
				T freshInstance = getCallback();
				if (freshInstance != null) {
					cachedInstance = freshInstance;
					cacheTimeout = now + TIMEOUT;
				}
			} catch (TException e) {
				LOGGER.warn("Could not retrieve fresh instance of " + getClass().getSimpleName(), e);
			}
		}
		return cachedInstance;
	}

}
