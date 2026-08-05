package br.com.jorgemelo.nimbusfilemanager.execution.application.constants;

import java.nio.file.Path;
import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;

/**
 * Central catalog of the stable execution message codes and the factory methods
 * that pair each code with its typed arguments. Every user-facing execution
 * message is authored here as a {@code backend.execution.*} bundle key, so a
 * message is emitted as {@code code + args} and localized only when the
 * response is read. Keeping the codes in one place makes the catalog auditable
 * and lets {@code BackendMessageKeysTest} find every key referenced in the
 * source.
 */
public final class ExecutionMessages {

	public static final String INVENTORY_STARTED = "backend.execution.inventoryStarted";
	public static final String COUNTING_FILES = "backend.execution.countingFiles";
	public static final String PROCESSING_FILES = "backend.execution.processingFiles";
	public static final String EXTRACTING_METADATA = "backend.execution.extractingMetadata";
	public static final String INVENTORY_COMPLETED = "backend.execution.inventoryCompleted";
	public static final String INVENTORY_CANCELLED = "backend.execution.inventoryCancelled";
	public static final String INVENTORY_REJECTED = "backend.execution.inventoryRejected";
	public static final String INVENTORY_FAILED = "backend.execution.inventoryFailed";
	public static final String INVENTORY_START_FAILED = "backend.execution.inventoryStartFailed";
	public static final String PROGRESS_UPDATED = "backend.execution.progressUpdated";
	public static final String PROCESSING_FILE = "backend.execution.processingFile";
	public static final String PROCESSING_ITEM = "backend.execution.processing";
	public static final String ERROR_PROCESSING_FILE = "backend.execution.errorProcessingFile";
	public static final String INTERRUPTED = "backend.execution.interrupted";
	public static final String SUPERSEDED = "backend.execution.superseded";
	public static final String ORGANIZATION_STARTED = "backend.execution.organizationStarted";
	public static final String PREVIEW_STARTED = "backend.execution.previewStarted";
	public static final String REJECTED_CONFLICTS = "backend.execution.rejectedConflicts";
	public static final String ORGANIZATION_FINISHED = "backend.execution.organizationFinished";
	public static final String PREVIEW_FINISHED = "backend.execution.previewFinished";
	public static final String ORGANIZATION_CANCELLED = "backend.execution.organizationCancelled";
	public static final String ORGANIZATION_REJECTED = "backend.execution.organizationRejected";
	public static final String ORGANIZATION_FAILED = "backend.execution.organizationFailed";
	public static final String RECONCILE_REPAIRED = "backend.execution.reconcileRepaired";
	public static final String OPERATION_FAILED = "backend.execution.operationFailed";

	/**
	 * Asked before undoing a run, and the only code here that is not recorded on
	 * an execution: it is text for a screen, resolved when the page is built, and
	 * so has no factory pairing it with an {@code ExecutionMessage}.
	 */
	public static final String UNDO_CONFIRM = "backend.execution.undoConfirm";

	private ExecutionMessages() {
	}

	public static ExecutionMessage inventoryStarted() {
		return of(INVENTORY_STARTED);
	}

	public static ExecutionMessage countingFiles() {
		return of(COUNTING_FILES);
	}

	public static ExecutionMessage processingFiles() {
		return of(PROCESSING_FILES);
	}

	public static ExecutionMessage extractingMetadata() {
		return of(EXTRACTING_METADATA);
	}

	public static ExecutionMessage inventoryCompleted() {
		return of(INVENTORY_COMPLETED);
	}

	public static ExecutionMessage inventoryCancelled() {
		return of(INVENTORY_CANCELLED);
	}

	public static ExecutionMessage inventoryRejected(String detail) {
		return of(INVENTORY_REJECTED, detail);
	}

	public static ExecutionMessage inventoryFailed(String detail) {
		return of(INVENTORY_FAILED, detail);
	}

	public static ExecutionMessage inventoryStartFailed(String detail) {
		return of(INVENTORY_START_FAILED, detail);
	}

	public static ExecutionMessage progressUpdated() {
		return of(PROGRESS_UPDATED);
	}

	public static ExecutionMessage processingFile(Path file) {
		return of(PROCESSING_FILE, file.toAbsolutePath().normalize().toString());
	}

	public static ExecutionMessage processing(String item) {
		return of(PROCESSING_ITEM, item);
	}

	public static ExecutionMessage errorProcessingFile(String path) {
		return of(ERROR_PROCESSING_FILE, path);
	}

	/**
	 * An operation whose loop died on the way. Shared by every operation that opens
	 * an execution: what differs between them is the detail, not the fact.
	 */
	public static ExecutionMessage operationFailed(String detail) {
		return of(OPERATION_FAILED, detail);
	}

	/**
	 * An identical request was already waiting, so this one had nothing left to
	 * add. Its own sentence, under its own status, so that reading the history to
	 * ask whether a cancel worked never finds this instead.
	 */
	public static ExecutionMessage executionSuperseded() {
		return of(SUPERSEDED);
	}

	public static ExecutionMessage executionInterrupted() {
		return of(INTERRUPTED);
	}

	public static ExecutionMessage organizationStarted() {
		return of(ORGANIZATION_STARTED);
	}

	public static ExecutionMessage previewStarted() {
		return of(PREVIEW_STARTED);
	}

	public static ExecutionMessage rejectedConflicts(long conflicts) {
		return of(REJECTED_CONFLICTS, conflicts);
	}

	public static ExecutionMessage organizationFinished(long moved, long skipped, long errors) {
		return of(ORGANIZATION_FINISHED, moved, skipped, errors);
	}

	public static ExecutionMessage previewFinished(long moved, long skipped, long errors) {
		return of(PREVIEW_FINISHED, moved, skipped, errors);
	}

	public static ExecutionMessage organizationCancelled(long moved, long skipped, long errors) {
		return of(ORGANIZATION_CANCELLED, moved, skipped, errors);
	}

	public static ExecutionMessage organizationRejected(String detail) {
		return of(ORGANIZATION_REJECTED, detail);
	}

	public static ExecutionMessage organizationFailed(String detail) {
		return of(ORGANIZATION_FAILED, detail);
	}

	public static ExecutionMessage reconcileRepaired(long renamed, long repairedPaths, long markedMissing) {
		return of(RECONCILE_REPAIRED, renamed, repairedPaths, markedMissing);
	}

	private static ExecutionMessage of(String code, Object... args) {
		return new ExecutionMessage(code, List.of(args));
	}
}