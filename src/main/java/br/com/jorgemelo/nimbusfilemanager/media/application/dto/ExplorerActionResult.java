package br.com.jorgemelo.nimbusfilemanager.media.application.dto;

/**
 * Outcome of an explorer action (quarantine, permanent delete, rename), already
 * carrying the message the dialog shows. The screen never composes text from
 * these counters: a partial run has to explain itself in one sentence written
 * here, where the reason is known.
 */
public record ExplorerActionResult(boolean success, String message, int processed, int skipped, int failed) {

	public static ExplorerActionResult of(String message) {
		return new ExplorerActionResult(true, message, 1, 0, 0);
	}

	public static ExplorerActionResult refused(String message) {
		return new ExplorerActionResult(false, message, 0, 0, 0);
	}
}