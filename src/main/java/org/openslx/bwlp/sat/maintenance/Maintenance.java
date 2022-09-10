package org.openslx.bwlp.sat.maintenance;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Maintenance extends Thread {

	private static final Logger LOGGER = LogManager.getLogger(Maintenance.class);

	private static Set<Maintenance> workers = new HashSet<>();

	private static BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(50);

	private Maintenance() {
		super();
		setPriority((Thread.MIN_PRIORITY + Thread.NORM_PRIORITY) / 2);
	}

	private synchronized static void ensureRunning() {
		if (workers.isEmpty() || (queue.size() > 5 && workers.size() < 3)) {
			Maintenance worker = new Maintenance();
			worker.start();
			workers.add(worker);
		}
	}

	public static void submit(Runnable job) throws InterruptedException {
		ensureRunning();
		queue.put(job);
	}

	public static boolean trySubmit(Runnable job) {
		ensureRunning();
		return queue.offer(job);
	}

	@Override
	public void run() {
		LOGGER.info("Maintenance Thread started");
		try {
			for (;;) {
				Runnable job = queue.take();
				runJob(job);
			}
		} catch (InterruptedException e) {
			LOGGER.warn("Maintenance Thread was interrupted!", e);
			Thread.currentThread().interrupt();
		}
	}

	private void runJob(Runnable job) {
		try {
			job.run();
		} catch (Throwable t) {
			LOGGER.warn("Uncaught exception in job '" + job.getClass().getSimpleName() + "'", t);
		}
	}

}
