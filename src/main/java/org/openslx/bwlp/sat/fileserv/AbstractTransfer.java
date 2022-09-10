package org.openslx.bwlp.sat.fileserv;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public abstract class AbstractTransfer {

	/**
	 * How long to keep this transfer information when the transfer is
	 * (potentially) done
	 */
	private static final long FINISH_TIMEOUT = TimeUnit.MINUTES.toMillis(3);

	/**
	 * How long to keep this transfer information when there are no active
	 * connections and the transfer seems unfinished
	 */
	private static final long IDLE_TIMEOUT = TimeUnit.HOURS.toMillis(6);

	/**
	 * How long to count this transfer towards active transfers when it has
	 * no active connection.
	 */
	private static final long HOT_IDLE_TIMEOUT = TimeUnit.MINUTES.toMillis(10);
	/**
	 * Time stamp of when (we think) the transfer finished. Clients can/might
	 * not tell us they're done, and simply taking "no active connection" as a
	 * sign the download is done might have unwanted effects if the user's
	 * connection drops for a minute. If this time stamp (plus a FINISH_TIMEOUT)
	 * passed,
	 * we consider the download done and flag it for removal.
	 * If set to zero, the transfer is not finished, or not assumed to have
	 * finished.
	 */
	protected final AtomicLong potentialFinishTime = new AtomicLong(0);

	/**
	 * Time of last activity on this transfer.
	 */
	protected final AtomicLong lastActivityTime = new AtomicLong(System.currentTimeMillis());

	private final String transferId;

	public AbstractTransfer(String transferId) {
		this.transferId = transferId;
	}

	/**
	 * Returns true if the transfer is considered completed.
	 * 
	 * @param now pass System.currentTimeMillis()
	 * @return true if the transfer is considered completed
	 */
	public boolean isComplete(long now) {
		long val = potentialFinishTime.get();
		return val != 0 && val + FINISH_TIMEOUT < now;
	}

	/**
	 * Returns true if there has been no activity on this transfer for a certain
	 * amount of time.
	 * 
	 * @param now pass System.currentTimeMillis()
	 * @return true if the transfer reached its idle timeout
	 */
	public final boolean hasReachedIdleTimeout(long now) {
		return getActiveConnectionCount() == 0 && lastActivityTime.get() + IDLE_TIMEOUT < now;
	}

	public final boolean countsTowardsConnectionLimit(long now) {
		return getActiveConnectionCount() > 0 || lastActivityTime.get() + HOT_IDLE_TIMEOUT > now;
	}

	public final String getId() {
		return transferId;
	}

	/**
	 * Returns true if this transfer would potentially accept new connections.
	 * This should NOT return false if there are too many concurrent
	 * connections, as this is used to signal the client whether to keep trying
	 * to connect.
	 * 
	 * @return true if this transfer would potentially accept new connections
	 */
	public abstract boolean isActive();

	/**
	 * Cancel this transfer, aborting all active connections and rejecting
	 * further incoming ones.
	 */
	public abstract void cancel();

	/**
	 * Returns number of active transfer connections.
	 * 
	 * @return number of active transfer connections
	 */
	public abstract int getActiveConnectionCount();

}
