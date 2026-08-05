package br.com.jorgemelo.nimbusfilemanager.worker.application;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A JVM that stays until it is killed, standing in for the application a worker
 * was told to outlive.
 *
 * <p>
 * A latch nobody counts down rather than a sleep: it ends when the process
 * ends, and never a moment before, so a test that waits on this process is
 * waiting on what it killed rather than on a timer that might have run out
 * first.
 */
final class SleepingParent {

	private static final long DEFAULT_DEADLINE_SECONDS = 300;

	private SleepingParent() {
	}

	public static void main(String[] args) throws InterruptedException {
		long seconds = args.length > 0 ? Long.parseLong(args[0]) : DEFAULT_DEADLINE_SECONDS;

		new CountDownLatch(1).await(seconds, TimeUnit.SECONDS);
	}
}