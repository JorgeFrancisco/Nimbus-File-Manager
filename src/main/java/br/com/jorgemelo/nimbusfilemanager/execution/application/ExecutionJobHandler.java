package br.com.jorgemelo.nimbusfilemanager.execution.application;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * What a worker knows how to run.
 *
 * <p>
 * One implementation per {@link ExecutionType}, which is a real point of
 * variation rather than an interface for its own sake: the dispatcher has to
 * pick a behaviour by a value read from a row, and the alternative is a switch
 * that every new type has to be added to by hand.
 *
 * <p>
 * A handler receives an execution that is already RUNNING, already owns its
 * path locks and has already been counted as an attempt. It is responsible for
 * the work and for the final status - the dispatcher only steps in when the
 * handler throws.
 *
 * <p>
 * It also receives the ownership of those locks, which is what a long
 * handler needs and a short one does not. Ownership can be lost while work is
 * in flight - the session holding the locks goes, and PostgreSQL releases them
 * without telling anybody - so anything that mutates the user's files asks
 * {@link ExecutionOwnership#assertMayGoOnWorking()} between batches and
 * immediately before each irreversible write. A handler that only reads has
 * nothing to guard and need not ask.
 */
public interface ExecutionJobHandler {

	ExecutionType type();

	/**
	 * How many of this kind may run at once in this worker. The default of one is
	 * the safe answer: a type that has never been asked to run twice at the same
	 * time should not start doing so because a handler was added.
	 */
	default int concurrencyLimit() {
		return 1;
	}

	/**
	 * Whether this kind of work is defined by a place on disk, and therefore has to
	 * hold the path locks before it runs.
	 *
	 * <p>
	 * The default is yes, and deliberately so: forgetting to name a path is what a
	 * new handler does by accident, and the answer that cannot corrupt anything is
	 * the one that refuses to start. A type that answers no is saying its work is
	 * described by a query rather than by a folder - drain every fingerprint that
	 * is missing, group everything already fingerprinted, delete catalog rows past
	 * their retention - and that nothing it does touches a tree another execution
	 * could be touching at the same time.
	 *
	 * <p>
	 * Answering no is not a way around the exclusion. A handler that can reach the
	 * port which changes the user's files has to answer yes, and that is checked
	 * rather than trusted - see {@code MutationBoundaryArchitectureTest}.
	 */
	default boolean requiresPathLock() {
		return true;
	}

	/**
	 * Whether an execution of this type that was abandoned halfway can simply be
	 * run again from the start.
	 *
	 * <p>
	 * True only where re-running is indistinguishable from running once: a walk
	 * that reads the tree and reconciles the catalog leaves nothing behind that a
	 * second pass would double. Anything that moves, writes or deletes the user's
	 * files answers no, because half of it already happened and the second run
	 * would begin from a world the first one changed. Those end as interrupted,
	 * and the divergence they left is what reconciliation is for.
	 *
	 * <p>
	 * The default is no, which is the answer that cannot corrupt anything.
	 */
	default boolean resumable() {
		return false;
	}

	void handle(Execution execution, ClaimedExecution claimed, ExecutionOwnership ownership);
}