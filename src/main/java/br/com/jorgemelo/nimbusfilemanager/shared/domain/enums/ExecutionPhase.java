package br.com.jorgemelo.nimbusfilemanager.shared.domain.enums;

/**
 * What a running execution is busy with, for the progress screen.
 *
 * <p>
 * Deliberately kept apart from {@link ExecutionStatus}: a phase is a detail of
 * how the work unfolds, and adding one should never touch the predicate the
 * queue claims by. It is null while an execution waits and after it ends -
 * there is no phase for work that is not happening.
 *
 * <p>
 * Not to be confused with {@link ExecutionPhaseType}, which answers a different
 * question: that one names the stretches whose wall time is measured for
 * telemetry, is finer grained and differs per kind of execution. This one is
 * the single coarse label the progress screen shows, the same for every type.
 */
public enum ExecutionPhase {

	/** Walking the file system, before anything is decided. */
	SCANNING,

	/** Reading, hashing, converting, moving - the work itself. */
	PROCESSING,

	/** Confirming what was written, after the work and before finishing. */
	VERIFYING
}