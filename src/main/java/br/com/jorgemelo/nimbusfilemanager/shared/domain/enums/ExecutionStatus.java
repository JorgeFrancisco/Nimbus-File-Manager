package br.com.jorgemelo.nimbusfilemanager.shared.domain.enums;

/**
 * Where an execution stands in the queue - and nothing else.
 *
 * <p>
 * This used to carry the phase as well: STARTED, SCANNING_FILES and
 * PROCESSING_FILES said what the work was busy with, while the remaining
 * constants said how it ended. Every caller that wanted "is it running?" had
 * to know which three of the nine meant that, and the queue would have needed
 * the same list in its predicate. The phase now lives in
 * {@link ExecutionPhase}, which is free to grow without any of that.
 */
public enum ExecutionStatus {

	/**
	 * Requested and waiting. Nobody owns it, no file has been touched, and it
	 * survives a restart of both processes - which is the whole reason the queue
	 * is in the database.
	 */
	PENDING,

	/**
	 * A worker owns it. Ownership is {@code claimed_by} plus a lease it renews;
	 * this constant only says the row is spoken for.
	 */
	RUNNING,

	FINISHED, FINISHED_WITH_ERRORS,

	INTERRUPTED, ERROR, CANCELLED,

	/**
	 * The execution deliberately did not go on, and nothing is wrong. Shown as a
	 * warning ("Rejeitado"), never with the red error styling, because in neither
	 * of its two cases did anything fail.
	 *
	 * <p>
	 * An organization run refused before any file was moved, because the plan held
	 * conflicts and conflicts were not allowed: a safe no-op, with {@code errors}
	 * left at 0.
	 *
	 * <p>
	 * Or a fingerprint backlog that stood aside for an inventory or a conversion -
	 * whether before it started or part-way through. Everything it computed is
	 * written and counted, so this one can carry counts, and the next run continues
	 * from what the database still says is missing. It is not {@link #CANCELLED},
	 * which means a person asked for the stop.
	 */
	REJECTED;

	/**
	 * Single source of truth for "the execution has stopped for good". Only
	 * {@link #PENDING} and {@link #RUNNING} are non-terminal; everything else is a
	 * final outcome. Callers (mapper, UI flags) rely on this instead of re-listing
	 * the terminal set.
	 */
	public boolean isTerminal() {
		return this != PENDING && this != RUNNING;
	}
}