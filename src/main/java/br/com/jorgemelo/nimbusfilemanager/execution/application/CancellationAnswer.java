package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.time.Duration;
import java.time.Instant;

/**
 * A cancellation answer and when it was obtained, so the next caller in a tight
 * loop can reuse it instead of asking the database again.
 *
 * @param cancelled what the row said
 * @param readAt when it said so
 */
record CancellationAnswer(boolean cancelled, Instant readAt) {

	boolean isFresh(Instant now, Duration freshness) {
		return readAt.plus(freshness).isAfter(now);
	}
}