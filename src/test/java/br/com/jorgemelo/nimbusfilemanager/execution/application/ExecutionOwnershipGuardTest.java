package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionPossession;
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueue;

/**
 * Which writes still belong to whoever is making them.
 *
 * <p>
 * The rule is narrower than "only the owner writes", and every case here is one
 * of the edges of that: a row nobody in this process is holding is not this
 * guard's business, and one that is being held is compared against the taking
 * it was held under - the attempt number, because a name that lost a row and
 * took it again is not the same taking.
 */
class ExecutionOwnershipGuardTest {

	private static final long EXECUTION_ID = 42L;

	private final ExecutionQueue executionQueue = mock(ExecutionQueue.class);
	private final ExecutionOwnershipGuard guard = new ExecutionOwnershipGuard(executionQueue);

	/**
	 * The ordinary case, and the reason the guard is safe to put in front of every
	 * write: an execution this process never took is one it has nothing to say
	 * about. Cancelling from the screen and the recovery pass both arrive this
	 * way.
	 */
	@Test
	void allowsAWriteAboutAnExecutionNobodyHereIsHolding() {
		assertThat(guard.isTheCurrentTaking(EXECUTION_ID, 1)).isTrue();
		assertThat(guard.claimCountOf(EXECUTION_ID)).isEmpty();
	}

	@Test
	void allowsTheTakingThatIsStillRegistered() {
		guard.takes(new ExecutionPossession(EXECUTION_ID, "worker-a", 1));

		assertThat(guard.isTheCurrentTaking(EXECUTION_ID, 1)).isTrue();
		assertThat(guard.claimCountOf(EXECUTION_ID)).hasValue(1);
	}

	/**
	 * The case a name alone cannot answer. Recovery gave the row back, the same
	 * worker took it again, and {@code claimed_by} reads exactly as it did before
	 * - so what is left over from the first taking has to be told apart by the
	 * attempt number, and refused.
	 */
	@Test
	void refusesAnEarlierTakingOfTheSameWorker() {
		guard.takes(new ExecutionPossession(EXECUTION_ID, "worker-a", 2));

		assertThat(guard.isTheCurrentTaking(EXECUTION_ID, 1)).isFalse();
	}

	@Test
	void forgetsThePossessionOnceTheTakingIsOver() {
		guard.takes(new ExecutionPossession(EXECUTION_ID, "worker-a", 1));
		guard.releases(EXECUTION_ID, 1);

		assertThat(guard.isTheCurrentTaking(EXECUTION_ID, 9)).isTrue();
		assertThat(guard.claimCountOf(EXECUTION_ID)).isEmpty();
	}

	/**
	 * A renewal round that ran to completion is the only thing that may say a
	 * taking is gone. What it learned is recorded against the taking it asked
	 * about, so the write it refuses afterwards is that one and no other.
	 */
	@Test
	void refusesATakingARenewalRoundWasToldIsNoLongerOurs() {
		ExecutionPossession taking = new ExecutionPossession(EXECUTION_ID, "worker-a", 1);

		guard.takes(taking);

		guard.renewalConfirmed(List.of(taking), List.of());

		assertThat(guard.isTheCurrentTaking(EXECUTION_ID, 1)).isFalse();
	}

	/**
	 * The case a worker name cannot answer, and the reason the identity is the
	 * taking. Recovery gives the row back and another loop of this same worker
	 * claims it, so for a while two takings of one execution are live in one
	 * process - and the older one must not be able to act.
	 */
	@Test
	void aLaterTakingOfTheSameRowReplacesTheEarlierOneForTheSameWorker() {
		guard.takes(new ExecutionPossession(EXECUTION_ID, "worker-a", 1));
		guard.takes(new ExecutionPossession(EXECUTION_ID, "worker-a", 2));

		assertThat(guard.isTheCurrentTaking(EXECUTION_ID, 1)).as("the taking that was replaced is over").isFalse();
		assertThat(guard.isTheCurrentTaking(EXECUTION_ID, 2)).as("and the one that replaced it is current").isTrue();
		assertThat(guard.claimCountOf(EXECUTION_ID)).hasValue(2);
	}

	/**
	 * The older taking is not refused here - it is sent to the database with its
	 * own number, which is the only thing that can say whether it is still in
	 * force. What must never happen is the two things this asserts: that it skips
	 * the database, or that it arrives there wearing the newer taking's number.
	 */
	@Test
	void aPinAlwaysCarriesTheCallersOwnAttemptNumberToTheDatabase() {
		guard.takes(new ExecutionPossession(EXECUTION_ID, "worker-a", 2));

		when(executionQueue.pin(EXECUTION_ID, "worker-a", 1)).thenReturn(false);
		when(executionQueue.pin(EXECUTION_ID, "worker-a", 2)).thenReturn(true);

		assertThat(guard.pin(EXECUTION_ID, 1)).as("the replaced taking is refused by the row, not by this class")
				.isFalse();
		assertThat(guard.pin(EXECUTION_ID, 2)).isTrue();

		verify(executionQueue).pin(EXECUTION_ID, "worker-a", 1);
	}

	/**
	 * The attempt-only hold, used where the run is already over: the telemetry
	 * consolidation. It goes to the database with the caller's own number, for
	 * the same reason {@code pin} does - only the row can say whether a later
	 * attempt has taken over.
	 */
	@Test
	void anAttemptPinAsksTheRowAboutTheCallersOwnAttempt() {
		guard.takes(new ExecutionPossession(EXECUTION_ID, "worker-a", 2));

		when(executionQueue.pinAttempt(EXECUTION_ID, 1)).thenReturn(false);
		when(executionQueue.pinAttempt(EXECUTION_ID, 2)).thenReturn(true);

		assertThat(guard.pinAttempt(EXECUTION_ID, 1)).as("the superseded attempt is refused by the row")
				.isFalse();
		assertThat(guard.pinAttempt(EXECUTION_ID, 2)).isTrue();
	}

	/**
	 * An execution nobody in this process holds is not fenced, exactly as with
	 * {@code pin}: those are commands about an execution rather than a run
	 * reporting on itself, and they answer to the row as it stands.
	 */
	@Test
	void anAttemptPinForARowNobodyHoldsIsNotFenced() {
		assertThat(guard.pinAttempt(EXECUTION_ID, 1)).isTrue();

		verifyNoInteractions(executionQueue);
	}

	/**
	 * The late {@code finally} of a taking that has been replaced. It has to be
	 * able to clean up after itself without taking the live registration with it -
	 * which is what would happen if the entry were keyed by the execution alone.
	 */
	@Test
	void theEndOfAnEarlierTakingDoesNotForgetTheOneThatReplacedIt() {
		guard.takes(new ExecutionPossession(EXECUTION_ID, "worker-a", 1));
		guard.takes(new ExecutionPossession(EXECUTION_ID, "worker-a", 2));

		guard.releases(EXECUTION_ID, 1);

		assertThat(guard.isTheCurrentTaking(EXECUTION_ID, 2)).as("the live taking survives the dead one's finally")
				.isTrue();
		assertThat(guard.claimCountOf(EXECUTION_ID)).hasValue(2);
	}

	/**
	 * And a renewal round that reported the earlier taking as gone must not put
	 * the later one in that state: they are different takings, so being told about
	 * one says nothing about the other.
	 */
	@Test
	void anEarlierTakingBeingReportedLostDoesNotMarkTheLaterOne() {
		ExecutionPossession earlier = new ExecutionPossession(EXECUTION_ID, "worker-a", 1);

		guard.takes(earlier);
		guard.takes(new ExecutionPossession(EXECUTION_ID, "worker-a", 2));

		guard.renewalConfirmed(List.of(earlier), List.of());

		assertThat(guard.isTheCurrentTaking(EXECUTION_ID, 2)).as("the later taking was not contaminated").isTrue();
	}
}