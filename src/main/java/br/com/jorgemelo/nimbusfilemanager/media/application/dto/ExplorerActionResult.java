package br.com.jorgemelo.nimbusfilemanager.media.application.dto;

import java.util.UUID;

/**
 * Outcome of an explorer action (quarantine, permanent delete, rename), already
 * carrying the message the dialog shows. The screen never composes text from
 * these counters: a partial run has to explain itself in one sentence written
 * on the backend, where the reason is known.
 *
 * <p>
 * {@code pending} is the third answer, and the one the screen has to tell apart
 * from the other two. The command is queued durably and the application waits a
 * short while for it; when it finishes in time this reports what happened, and
 * when it does not, {@code pending} says the work is still coming and
 * {@code executionId} says where to watch for it. Pending is not failure - the
 * request was accepted, and refusing to say so would be the one reading a
 * person cannot recover from.
 */
public record ExplorerActionResult(boolean success, String message, int processed, int skipped, int failed,
		boolean pending, UUID executionId) {

	/** Refused before anything was queued, and therefore before anything ran. */
	public static ExplorerActionResult refused(String message) {
		return new ExplorerActionResult(false, message, 0, 0, 0, false, null);
	}

	/** Queued, and still going when the screen stopped waiting for it. */
	public static ExplorerActionResult pending(String message, UUID executionId) {
		return new ExplorerActionResult(true, message, 0, 0, 0, true, executionId);
	}

	public static ExplorerActionResult of(boolean success, String message, int processed, int skipped, int failed) {
		return new ExplorerActionResult(success, message, processed, skipped, failed, false, null);
	}
}