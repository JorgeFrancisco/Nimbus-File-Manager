package br.com.jorgemelo.nimbusfilemanager.execution.domain.enums;

/**
 * Whether there is a time remaining to show, and when there is not, which of the
 * two reasons it is.
 *
 * <p>
 * The distinction is the whole point of naming a state instead of returning a
 * sentinel number. "We cannot say yet" and "there is nothing honest to say here"
 * read identically as {@code -1} and were shown identically as "calculating…",
 * so a workload that would never produce an estimate looked like one still
 * warming up - for hours.
 */
public enum EtaState {

	/** A remaining time is available and carries a number. */
	AVAILABLE,

	/**
	 * There is an honest denominator, but not yet enough measurement to divide by
	 * it. This one really does resolve on its own.
	 */
	CALCULATING,

	/**
	 * This work has no honest denominator, so no estimate will ever appear. A
	 * dataset update measured in stages of wildly different cost and a similarity
	 * analysis whose cost grows with pairs rather than files are both this.
	 */
	NOT_APPLICABLE
}