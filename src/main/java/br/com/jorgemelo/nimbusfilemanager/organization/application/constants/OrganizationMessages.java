package br.com.jorgemelo.nimbusfilemanager.organization.application.constants;

import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;

/**
 * The organization message codes resolved from the bundles, kept apart from
 * {@link OrganizationConstants} because these are keys of user-facing text
 * rather than parameters of the feature.
 *
 * <p>
 * The two confirmations exist as a pair because the answer to "how much is
 * about to move?" depends on whether a preview has been run, and choosing
 * between them is a decision - which belongs here and in the controller, never
 * in the template.
 */
public final class OrganizationMessages {

	/**
	 * The undo statuses, as codes rather than text. The reversal happens in the
	 * worker now, which has no request behind it and therefore no language.
	 */
	public static ExecutionMessage undoQueued() {
		return of("backend.undo.queued");
	}

	public static ExecutionMessage undoCompleted(long undone, long skipped, long errors) {
		return of("backend.undo.completed", undone, skipped, errors);
	}

	public static ExecutionMessage undoCancelled(long undone, long skipped, long errors) {
		return of("backend.undo.cancelled", undone, skipped, errors);
	}

	public static ExecutionMessage undoInterrupted(long undone, long skipped, long errors) {
		return of("backend.undo.interrupted", undone, skipped, errors);
	}

	public static ExecutionMessage undoFailed(String detail) {
		return of("backend.execution.operationFailed", detail);
	}

	private static ExecutionMessage of(String code, Object... args) {
		return new ExecutionMessage(code, List.of(args));
	}

	/** Asked before a preview, when nobody knows yet how much would move. */
	public static final String EXECUTE_CONFIRM = "backend.organization.executeConfirm";

	/** Asked with a plan in hand: how many files, and how much they weigh. */
	public static final String EXECUTE_CONFIRM_PLANNED = "backend.organization.executeConfirmPlanned";

	private OrganizationMessages() {
	}
}