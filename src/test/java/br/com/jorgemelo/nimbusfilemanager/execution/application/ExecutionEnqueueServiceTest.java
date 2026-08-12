package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionStatusNames;
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueueNotifier;
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.QueueAdmissionLockRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * Asking for work: the row goes in PENDING and nothing runs.
 *
 * <p>
 * The two things worth asserting are the state the request enters the queue in
 * - anything else would be a request that looks like it already started - and
 * that a duplicate is answered rather than raised, because the database
 * refusing it is the normal outcome of two clicks, not an error.
 */
class ExecutionEnqueueServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-04T10:00:00Z");

	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);
	private final ExecutionQueueNotifier executionQueueNotifier = mock(ExecutionQueueNotifier.class);
	private final QueueAdmissionLockRepository queueAdmissionLockRepository = mock(QueueAdmissionLockRepository.class);

	private final ExecutionEnqueueService service = new ExecutionEnqueueService(executionRepository,
			executionQueueNotifier, queueAdmissionLockRepository, mock(PlatformTransactionManager.class),
			Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void queuesTheRequestAsPendingAndAvailableAtOnce() {
		when(executionRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

		Optional<Execution> queued = service.enqueue(request());

		assertThat(queued).isPresent();
		assertThat(queued.get().getStatus()).isEqualTo(ExecutionStatus.PENDING);
		assertThat(queued.get().getCreatedAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
		assertThat(queued.get().getAvailableAt()).isEqualTo(queued.get().getCreatedAt());
	}

	/**
	 * A timer request with no dedup key. Refusing needs something to be equivalent
	 * to, and without a key there is nothing - so the request goes through instead
	 * of being silently swallowed by a coalescing rule it cannot participate in.
	 */
	@Test
	void queuesARequestWithoutADedupKeyBecauseNothingCanBeEquivalentToIt() {
		when(executionRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

		Execution keyless = Execution.builder().executionType(ExecutionType.INVENTORY).sourcePath("D:\fotos")
				.build();

		Optional<Execution> queued = service.enqueueUnlessAlreadyActive(keyless);

		assertThat(queued).isPresent();

		verify(executionRepository, never()).findFirstByExecutionTypeAndDedupKeyAndStatusInOrderByCreatedAtDesc(
				any(), any(), any());
	}

	/**
	 * A request nobody has taken has not started, and stamping it would make every
	 * duration on the history screen include the time spent waiting.
	 */
	@Test
	void leavesTheStartTimeToWhoeverClaimsIt() {
		when(executionRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

		Execution alreadyStamped = request();
		alreadyStamped.setStartedAt(LocalDateTime.now());

		assertThat(service.enqueue(alreadyStamped).orElseThrow().getStartedAt()).isNull();
	}

	/**
	 * The partial unique index refusing the insert is how two simultaneous clicks
	 * are settled - a look-then-act check would let both through.
	 */
	@Test
	void answersEmptyWhenAnIdenticalRequestIsAlreadyQueued() {
		when(executionRepository.saveAndFlush(any())).thenThrow(refusedBy("ux_execution_pending_dedup"));

		assertThat(service.enqueue(request())).isEmpty();
	}

	/**
	 * The signal goes out with the row, and only for a row that was actually
	 * written. A worker woken for a request the database refused would query the
	 * queue and find nothing - harmless, but it would also mean the notification
	 * was announcing something other than "there is work", which is the only thing
	 * it is allowed to mean.
	 */
	@Test
	void tellsTheWorkerOnlyAboutRequestsThatWereQueued() {
		when(executionRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

		service.enqueue(request());

		verify(executionQueueNotifier).workWasQueued();
	}

	@Test
	void doesNotSignalWhenTheRequestWasRefusedAsADuplicate() {
		when(executionRepository.saveAndFlush(any())).thenThrow(refusedBy("ux_execution_pending_dedup"));

		assertThat(service.enqueue(request())).isEmpty();

		verify(executionQueueNotifier, never()).workWasQueued();
	}

	/**
	 * A duplicate answered with the request already waiting, which is what makes a
	 * second click on the same thing harmless: the screen is told about the work
	 * that is already coming rather than about an error.
	 */
	@Test
	void answersADuplicateWithTheRequestThatIsAlreadyQueued() {
		Execution waiting = request();

		nothingWaiting();

		when(executionRepository.saveAndFlush(any())).thenThrow(refusedBy("ux_execution_pending_dedup"));
		when(executionRepository.findFirstByExecutionTypeAndDedupKeyAndStatusInOrderByCreatedAtDesc(
				ExecutionType.INVENTORY, "d:\\fotos", ExecutionStatusNames.ACTIVE)).thenReturn(Optional.of(waiting));

		assertThat(service.enqueueOrExisting(request())).isSameAs(waiting);
	}

	/**
	 * The request already on the queue is answered without an insert being
	 * attempted at all - which is the point, because the insert that gets refused
	 * writes an error to the log that no {@code catch} here is early enough to
	 * explain. This is what a boot looks like after recovery put the previous
	 * request back: the row was committed long before anybody asked again.
	 */
	@Test
	void answersWithTheRequestAlreadyWaitingWithoutTryingToInsert() {
		Execution waiting = request();

		// Stubbed so that an attempted insert would succeed: what has to fail if this
		// regresses is the verification below, not a mock answering null.
		when(executionRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(executionRepository.findFirstByExecutionTypeAndDedupKeyAndStatusInOrderByCreatedAtDesc(
				ExecutionType.INVENTORY, "d:\\fotos", Set.of(ExecutionStatus.PENDING)))
				.thenReturn(Optional.of(waiting));

		assertThat(service.enqueueOrExisting(request())).isSameAs(waiting);

		verify(executionRepository, never()).saveAndFlush(any());
	}

	/**
	 * And the check asks only about what is <em>waiting</em>. One running does not
	 * forbid a successor - that is the 1 + 1 the two partial indexes exist for -
	 * so a request made while an identical one runs still has to be queued.
	 * Answering with the running one would turn the rule into 1 + 0 without
	 * anything failing.
	 */
	@Test
	void queuesASuccessorWhileAnIdenticalRequestIsStillRunning() {
		nothingWaiting();

		when(executionRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

		Execution successor = service.enqueueOrExisting(request());

		assertThat(successor.getStatus()).isEqualTo(ExecutionStatus.PENDING);

		verify(executionRepository).saveAndFlush(any());
	}

	/**
	 * A violation of some other constraint is not this one's business. Reporting
	 * it as "already queued" would hand back a row that answers a different
	 * question, or raise for a reason that has nothing to do with what went wrong.
	 *
	 * <p>
	 * A violation carrying no constraint name is treated the same way: the name is
	 * the only thing that says this was deduplication, and guessing without it
	 * would be exactly the mistake the check exists to stop.
	 */
	@ParameterizedTest
	@NullSource
	@ValueSource(strings = "uk_execution_public_id")
	void doesNotTreatSomeOtherIntegrityViolationAsADuplicate(String constraint) {
		nothingWaiting();

		when(executionRepository.saveAndFlush(any())).thenThrow(refusedBy(constraint));

		Execution request = request();

		assertThatExceptionOfType(DataIntegrityViolationException.class)
				.isThrownBy(() -> service.enqueueOrExisting(request));
	}

	/**
	 * And one that is not a constraint violation at all - a null in a column that
	 * forbids one, a foreign key - reaches the caller rather than being answered
	 * with somebody else's execution.
	 */
	@Test
	void doesNotTreatAnIntegrityViolationThatNamesNoConstraintAsADuplicate() {
		nothingWaiting();

		when(executionRepository.saveAndFlush(any()))
				.thenThrow(new DataIntegrityViolationException("null value violates not-null constraint"));

		Execution request = request();

		assertThatExceptionOfType(DataIntegrityViolationException.class)
				.isThrownBy(() -> service.enqueueOrExisting(request));
	}

	/**
	 * The refusal and the lookup that follows it are two statements, and the row
	 * that caused the refusal can finish between them. Answering that with an
	 * error would fail a request for the very reason it should have succeeded -
	 * what was in its way is gone - so it is simply asked again.
	 */
	@Test
	void asksAgainWhenTheRequestItCollidedWithIsNoLongerQueued() {
		Execution acceptedOnTheSecondAsking = request();

		when(executionRepository.saveAndFlush(any())).thenThrow(refusedBy("ux_execution_pending_dedup"))
				.thenReturn(acceptedOnTheSecondAsking);
		when(executionRepository.findFirstByExecutionTypeAndDedupKeyAndStatusInOrderByCreatedAtDesc(any(), any(),
				any())).thenReturn(Optional.empty());

		assertThat(service.enqueueOrExisting(request())).isSameAs(acceptedOnTheSecondAsking);
	}

	/**
	 * The index refused the insert and nothing is waiting, which cannot both be
	 * true. Raising says so instead of answering with a row nobody has.
	 */
	@Test
	void raisesWhenADuplicateIsRefusedButNothingIsQueued() {
		when(executionRepository.saveAndFlush(any())).thenThrow(refusedBy("ux_execution_pending_dedup"));
		when(executionRepository.findFirstByExecutionTypeAndDedupKeyAndStatusInOrderByCreatedAtDesc(any(), any(),
				any())).thenReturn(Optional.empty());

		assertThatIllegalStateException().isThrownBy(() -> service.enqueueOrExisting(request()));
	}

	/**
	 * A refusal shaped the way one really arrives: the constraint name travels
	 * inside the Hibernate exception, and it is the only thing that says whether a
	 * violation means "already queued" or something else entirely.
	 */
	private void nothingWaiting() {
		when(executionRepository.findFirstByExecutionTypeAndDedupKeyAndStatusInOrderByCreatedAtDesc(any(), any(),
				eq(Set.of(ExecutionStatus.PENDING)))).thenReturn(Optional.empty());
	}

	private DataIntegrityViolationException refusedBy(String constraint) {
		return new DataIntegrityViolationException("could not execute statement",
				new ConstraintViolationException("duplicate key value violates unique constraint",
						new SQLException("duplicate key", "23505"), constraint));
	}

	private Execution request() {
		return Execution.builder().executionType(ExecutionType.INVENTORY).sourcePath("D:\\fotos")
				.dedupKey("d:\\fotos").build();
	}
}