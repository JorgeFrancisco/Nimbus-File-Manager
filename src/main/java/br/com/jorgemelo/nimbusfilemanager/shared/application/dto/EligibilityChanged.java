package br.com.jorgemelo.nimbusfilemanager.shared.application.dto;

/**
 * Files entered or left the set a duplicate analysis is allowed to look at, and
 * the operation that moved them has committed.
 *
 * <p>
 * The fact and nothing else. It names no medium, no threshold, no algorithm and
 * no run mode, because the operations that publish it - excluding a file,
 * quarantining a batch, restoring one, reorganising a folder - have no business
 * knowing that similarity exists. Whoever consumes it is responsible for
 * deciding what, if anything, needs recomputing. That is what keeps the
 * dependency pointing at this package instead of at the duplicate domain.
 *
 * <p>
 * <b>One per operation, never one per file.</b> A quarantine batch moves
 * thousands of files and publishes this once, at the boundary of the operation,
 * and only if at least one file really changed eligibility. An operation that
 * changed nothing publishes nothing.
 *
 * <p>
 * {@code origin} names the operation, and exists for one concrete reason: the
 * consumer's failure path logs a warning, and a warning that cannot say what
 * changed is a warning nobody can act on.
 */
public record EligibilityChanged(String origin) {
}