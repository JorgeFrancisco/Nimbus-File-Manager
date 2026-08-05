package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.mockito.Mockito.mock;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionPossession;
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueue;

/**
 * Takings for the many unit tests whose subject is something else entirely - an
 * organization run, a quarantine purge, a conversion batch - and that only need
 * a taking to write under.
 *
 * <p>
 * Real {@link ExecutionOwnership} objects rather than mocks, because the answer
 * that matters here is one the class computes rather than one a stub is told to
 * give: a mock defaults to "not owned", which would silently turn every write
 * under test into a no-op and let a test pass by asserting nothing happened.
 *
 * <p>
 * None of them holds a path lock. What these tests need from a taking is the
 * half that says which row and which attempt is writing; exclusion over a tree
 * is a property of PostgreSQL and belongs in an integration test.
 */
public final class Takings {

	private Takings() {
	}

	/** A taking in force over the row, before any attempt has been counted. */
	public static ExecutionOwnership owning(long executionId) {
		return owning(executionId, guard());
	}

	/**
	 * The same, over a guard the test holds: what makes it possible to register a
	 * later taking of the row afterwards and watch this one stop being current.
	 */
	public static ExecutionOwnership owning(long executionId, ExecutionOwnershipGuard guard) {
		return new ExecutionOwnership(executionId, null, null, guard);
	}

	/**
	 * The named attempt of the row, registered with the guard so that a later one
	 * of the same row can be told apart from it.
	 */
	public static ExecutionOwnership taking(long executionId, int claimCount, ExecutionOwnershipGuard guard) {
		ExecutionOwnership ownership = new ExecutionOwnership(executionId, null, null, guard);

		ownership.attemptStarted(claimCount);

		return ownership;
	}

	/**
	 * A taking that is over. Every write made under it is refused, which is what a
	 * caller that stopped at the wrong moment looks like from the row's side.
	 */
	public static ExecutionOwnership over(long executionId) {
		ExecutionOwnership ownership = owning(executionId);

		ownership.close();

		return ownership;
	}

	/**
	 * A taking the row has moved on from: the same worker claimed it again, and
	 * only the attempt number tells the two runs apart.
	 *
	 * <p>
	 * What the cooperative checkpoints are there to notice. The answer comes from
	 * memory and saves work rather than authorising any - a write made under this
	 * one is refused by the pin, which is a different test against a real database.
	 */
	public static ExecutionOwnership replaced(long executionId) {
		ExecutionOwnershipGuard guard = guard();

		ExecutionOwnership ownership = taking(executionId, 1, guard);

		guard.takes(new ExecutionPossession(executionId, "worker-that-came-back", 2));

		return ownership;
	}

	/**
	 * A taking whose {@code pin} always succeeds without asking the database, for
	 * the tests whose subject is what an operation <em>does</em> rather than who is
	 * entitled to do it.
	 *
	 * <p>
	 * Named for what it is missing. A test that reaches for this is saying, in the
	 * one word, that it is not exercising the fencing - so a fenced write that
	 * quietly stopped being fenced would not be caught here, and is not expected to
	 * be. The T4 boundaries are proved against a real PostgreSQL by the tests that
	 * use {@link #fenced}.
	 *
	 * <p>
	 * Nothing is stubbed to make this true: no possession is registered, and an
	 * execution this process is not holding is one the guard does not fence, which
	 * is the same answer a screen-driven command gets.
	 */
	public static ExecutionOwnership unfenced(long executionId) {
		return taking(executionId, 1, guard());
	}

	/**
	 * A taking registered with the guard, so that {@code pin} really goes to the
	 * database and answers about the row as it stands.
	 *
	 * <p>
	 * The guard has to be the one wired over the real queue for this to mean
	 * anything, which is why it is a parameter: this is the shape a T4 test needs,
	 * and the shape {@link #unfenced} deliberately is not.
	 */
	public static ExecutionOwnership fenced(long executionId, String workerId, int claimCount,
			ExecutionOwnershipGuard guard) {
		guard.takes(new ExecutionPossession(executionId, workerId, claimCount));

		return taking(executionId, claimCount, guard);
	}

	private static ExecutionOwnershipGuard guard() {
		return new ExecutionOwnershipGuard(mock(ExecutionQueue.class));
	}
}