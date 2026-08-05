package br.com.jorgemelo.nimbusfilemanager.worker.infrastructure;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A process that stays alive until something ends it, so the supervisor can be
 * watched against a real one.
 *
 * <p>
 * The out-of-process tests need a child with a real pid that does not exit on
 * its own. Starting the whole application would be testing the application;
 * this tests the boundary - that the handle belongs to the process that was
 * started, that its exit is noticed, and that stopping it stops it.
 *
 * <p>
 * Waits on a latch nobody counts down rather than sleeping, and takes a
 * deadline so a child that somehow outlives its test still goes away instead of
 * lingering on a developer's machine.
 */
public final class SleepingProcess {

	private static final long DEFAULT_DEADLINE_SECONDS = 300;

	private SleepingProcess() {
	}

	public static void main(String[] args) throws InterruptedException {
		long seconds = args.length > 0 ? Long.parseLong(args[0]) : DEFAULT_DEADLINE_SECONDS;

		new CountDownLatch(1).await(seconds, TimeUnit.SECONDS);
	}
}