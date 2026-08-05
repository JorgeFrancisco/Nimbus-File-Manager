package br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants;

import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;

/**
 * The status a quarantine batch writes on its execution, as a key and its
 * arguments.
 *
 * <p>
 * Never as resolved text: the batch runs in the worker, which has no request
 * behind it and therefore no language. Kept as a code, it is localized on read,
 * where the request is.
 */
public final class QuarantineMessages {

	/**
	 * The one sentence here that is not written on an execution, because it is
	 * what is said <em>instead</em> of making one: every quarantine operation is
	 * defined by that folder, so without it there is no request to queue.
	 */
	public static final String FOLDER_NOT_CONFIGURED = "backend.quarantine.folderNotConfigured";

	public static ExecutionMessage restoreStarted(int selected) {
		return of("backend.quarantine.restoreStarted", selected);
	}

	public static ExecutionMessage batchCompleted(int restored, int conflicts, int originMissing, int errors) {
		return of("backend.quarantine.batchCompleted", restored, conflicts, originMissing, errors);
	}

	/**
	 * A restore that stopped early says so with the same four counts. What it
	 * counted was moved back under the locks and verified byte for byte; what
	 * stops is the rest of the selection.
	 */
	public static ExecutionMessage batchCancelled(int restored, int conflicts, int originMissing, int errors) {
		return of("backend.quarantine.batchCancelled", restored, conflicts, originMissing, errors);
	}

	public static ExecutionMessage batchInterrupted(int restored, int conflicts, int originMissing, int errors) {
		return of("backend.quarantine.batchInterrupted", restored, conflicts, originMissing, errors);
	}

	public static ExecutionMessage purgeStarted(int selected) {
		return of("backend.quarantine.purgeStarted", selected);
	}

	public static ExecutionMessage purgeCompleted(int purged, int skipped, int busy, int errors) {
		return of("backend.quarantine.purgeCompleted", purged, skipped, busy, errors);
	}

	public static ExecutionMessage purgeCancelled(int purged, int skipped, int busy, int errors) {
		return of("backend.quarantine.purgeCancelled", purged, skipped, busy, errors);
	}

	public static ExecutionMessage purgeInterrupted(int purged, int skipped, int busy, int errors) {
		return of("backend.quarantine.purgeInterrupted", purged, skipped, busy, errors);
	}

	/**
	 * The three reasons a purge can delete nothing at all. Each says what to do
	 * next, because a pass that deleted nothing is what the person needs told in
	 * words rather than as a counter that reads like success.
	 */
	public static ExecutionMessage heldByAnotherOperation(int busy) {
		return of("backend.quarantine.delete.busy", busy);
	}

	public static ExecutionMessage couldNotDeleteFromDisk(int errors) {
		return of("backend.quarantine.delete.errors", errors);
	}

	public static ExecutionMessage alreadyLeftQuarantine(int skipped) {
		return of("backend.quarantine.delete.skipped", skipped);
	}

	public static ExecutionMessage cleanupStarted(int selected) {
		return of("backend.quarantine.cleanupStarted", selected);
	}

	public static ExecutionMessage cleanupCompleted(int removed, int kept) {
		return of("backend.quarantine.cleanupCompleted", removed, kept);
	}

	private static ExecutionMessage of(String code, Object... args) {
		return new ExecutionMessage(code, List.of(args));
	}

	private QuarantineMessages() {
	}
}