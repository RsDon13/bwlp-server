package org.openslx.bwlp.sat.maintenance;

import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openslx.bwlp.sat.mail.MailQueue;
import org.openslx.util.QuickTimer;
import org.openslx.util.QuickTimer.Task;

public class MailFlusher implements Runnable {

	private static final Logger LOGGER = LogManager.getLogger(MailFlusher.class);

	private static final MailFlusher instance = new MailFlusher();

	private static long blockedUntil = 0;

	/**
	 * Initialize the task. This schedules a timer that runs
	 * every 6 minutes.
	 */
	public synchronized static void init() {
		if (blockedUntil != 0)
			return;
		LOGGER.debug("Initializing mail flusher");
		blockedUntil = 1;
		QuickTimer.scheduleAtFixedRate(new Task() {
			@Override
			public void fire() {
				if (blockedUntil > System.currentTimeMillis())
					return;
				start();
			}
		}, TimeUnit.MINUTES.toMillis(6), TimeUnit.MINUTES.toMillis(10));
	}

	public synchronized static void start() {
		if (blockedUntil > System.currentTimeMillis())
			return;
		if (Maintenance.trySubmit(instance)) {
			blockedUntil = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10);
		}
	}

	@Override
	public void run() {
		try {
			MailQueue.flush();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
