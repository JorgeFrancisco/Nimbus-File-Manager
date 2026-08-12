package br.com.jorgemelo.nimbusfilemanager.shared.domain.enums;

/**
 * How far an operation got.
 *
 * <p>
 * {@code SIMULATED} is gone: a dry run leaves a plan and never an operation, and
 * no code path ever wrote it. {@code UNDO_ERROR} is gone for a sharper reason -
 * it recorded the outcome of one operation on the row of another. A failed undo
 * is a failed reversing movement; the movement it was trying to reverse never
 * stopped being {@link #MOVED}.
 */
public enum MovementStatus {

	/** Persisted, nothing touched yet. The only state a retry may resume from. */
	PENDING,

	/** The file moved, and a fact was recorded under the reserved identity. */
	MOVED,

	/** The operation decided against an effect; {@code reason} says why. */
	SKIPPED,

	/**
	 * The operation failed without an effect. The detail belongs to
	 * {@code execution_error}, which has been the authority on why since the
	 * movement stopped carrying its own message.
	 */
	ERROR,

	/**
	 * This operation's effect was later reversed by a different movement, under the
	 * execution that did the reversing. The fact this one produced still stands -
	 * it did happen - and the reversal is a fact of its own.
	 */
	UNDONE
}