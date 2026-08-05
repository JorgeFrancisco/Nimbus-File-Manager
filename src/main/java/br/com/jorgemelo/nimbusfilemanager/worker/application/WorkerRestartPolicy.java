package br.com.jorgemelo.nimbusfilemanager.worker.application;

import java.time.Duration;

/**
 * When to start another worker, and when to stop trying.
 *
 * <p>
 * Restarting at once is right for the case it was written for - a worker that
 * ran for hours and then died - and wrong for the one that turned up later: a
 * worker that cannot start at all. An incompatible schema, a database that
 * never answers, a jar that will not load: each of those ends the process in a
 * second, and an immediate restart makes the application spawn JVMs as fast as
 * they can fail.
 *
 * <p>
 * What separates the two is not the exit code, which a JVM killed by the system
 * does not get to choose, but how long it lived. A worker that reached the
 * threshold below did its job for a while and deserves an immediate
 * replacement; one that did not is failing to start, and each further attempt
 * waits longer than the last until the attempts run out.
 *
 * <p>
 * Pure, and separate from the supervisor, because these are the decisions worth
 * asserting and the supervisor's own job is processes.
 */
public final class WorkerRestartPolicy {

	/**
	 * Past this, a worker is taken to have been working. Comfortably longer than
	 * starting takes and far shorter than anything anyone would call uptime.
	 */
	private static final Duration HEALTHY = Duration.ofSeconds(60);

	private static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);

	/** Where doubling stops: a minute between attempts is already patient. */
	private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);

	/**
	 * Eight failures in a row, with the delays below, is around four minutes of
	 * trying. Whatever is wrong by then is not going to be fixed by another JVM.
	 */
	private static final int MAX_CONSECUTIVE_FAILURES = 8;

	private WorkerRestartPolicy() {
	}

	/**
	 * Whether a worker that lived this long counts as having failed to start.
	 */
	public static boolean failedToStart(Duration lifetime) {
		return lifetime.compareTo(HEALTHY) < 0;
	}

	/**
	 * How many consecutive start failures there are now, given how long the worker
	 * that just exited lived. A worker that was working resets the count, which is
	 * what keeps a long-running installation from ever reaching the give-up limit.
	 */
	public static int consecutiveFailuresAfter(int previousFailures, Duration lifetime) {
		return failedToStart(lifetime) ? previousFailures + 1 : 0;
	}

	/**
	 * How long to wait before the next attempt. Zero for a worker that was
	 * working - that case should be replaced at once - and doubling for one that
	 * is not.
	 */
	public static Duration delayAfter(int consecutiveFailures) {
		if (consecutiveFailures <= 0) {
			return Duration.ZERO;
		}

		Duration doubled = FIRST_BACKOFF.multipliedBy(1L << Math.min(consecutiveFailures - 1, 20));

		return doubled.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : doubled;
	}

	/**
	 * Whether to stop replacing the worker. Giving up is louder than looping: the
	 * log says it once, and the application goes on serving screens without a
	 * worker, which is a product missing background work rather than a machine
	 * spawning processes.
	 */
	public static boolean givesUpAfter(int consecutiveFailures) {
		return consecutiveFailures >= MAX_CONSECUTIVE_FAILURES;
	}
}