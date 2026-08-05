package br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * The queue against a real PostgreSQL, because everything worth asserting here
 * is behaviour of the database rather than of the code: {@code SKIP LOCKED}
 * handing the same row to exactly one of two claimers, a partial unique index
 * refusing a duplicate request, and an UPDATE that has to fail when the row
 * moved on.
 *
 * <p>
 * Deliberately not {@code @Transactional}: two claimers in one transaction
 * would see each other's uncommitted work, which is the opposite of what two
 * processes do.
 */
@SpringBootTest
@Testcontainers
class ExecutionQueueIntegrationTest {

	private static final int MAX_CLAIMS = 3;
	private static final int LEASE_SECONDS = 120;

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

	@Autowired
	private ExecutionQueue executionQueue;

	@Autowired
	private ExecutionRepository executionRepository;

	/**
	 * Every test starts from an empty queue. This class cannot be transactional -
	 * two claimers sharing one transaction would see each other's uncommitted work
	 * - so rows survive the test that wrote them, and a leftover RUNNING row with
	 * the same deduplication key is exactly what the partial index refuses.
	 */
	@BeforeEach
	void emptyTheQueue() {
		executionRepository.deleteAll();
	}

	@Test
	void reservesThePendingExecutionAndLeavesTheRowOwned() {
		Execution pending = enqueue(ExecutionType.INVENTORY, "D:\\fotos", 0);

		Optional<ClaimedExecution> claimed = reserve("worker-a");

		Assertions.assertThat(claimed).isPresent();
		Assertions.assertThat(claimed.get().id()).isEqualTo(pending.getId());
		Assertions.assertThat(claimed.get().sourcePath()).isEqualTo("D:\\fotos");

		Execution stored = executionRepository.findById(pending.getId()).orElseThrow();

		Assertions.assertThat(stored.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
		Assertions.assertThat(stored.getClaimedBy()).isEqualTo("worker-a");
		Assertions.assertThat(stored.getLeaseUntil()).isNotNull();
	}

	@Test
	void doesNotChargeAnAttemptForMerelyReserving() {
		Execution pending = enqueue(ExecutionType.RECONCILE, "D:\\a", 0);

		reserve("worker-a");

		Assertions.assertThat(executionRepository.findById(pending.getId()).orElseThrow().getClaimCount()).isZero();
	}

	@Test
	void chargesTheAttemptOnlyWhenTheWorkIsAboutToStart() {
		Execution pending = enqueue(ExecutionType.RECONCILE, "D:\\b", 0);

		reserve("worker-a");

		Assertions.assertThat(executionQueue.countAttempt(pending.getId(), "worker-a", MAX_CLAIMS)).isTrue();
		Assertions.assertThat(executionRepository.findById(pending.getId()).orElseThrow().getClaimCount()).isEqualTo(1);
	}

	@Test
	void refusesToChargeAnAttemptForAnotherWorker() {
		Execution pending = enqueue(ExecutionType.RECONCILE, "D:\\c", 0);

		reserve("worker-a");

		Assertions.assertThat(executionQueue.countAttempt(pending.getId(), "worker-b", MAX_CLAIMS)).isFalse();
		Assertions.assertThat(executionRepository.findById(pending.getId()).orElseThrow().getClaimCount()).isZero();
	}

	@Test
	void refusesToChargeAnAttemptOnceTheBudgetIsSpent() {
		Execution poison = enqueue(ExecutionType.RECONCILE, "D:\\d", MAX_CLAIMS);

		poison.setStatus(ExecutionStatus.RUNNING);
		poison.setClaimedBy("worker-a");

		executionRepository.save(poison);

		Assertions.assertThat(executionQueue.countAttempt(poison.getId(), "worker-a", MAX_CLAIMS)).isFalse();
	}

	@Test
	void neverReservesAnExecutionThatSpentItsAttempts() {
		enqueue(ExecutionType.RECONCILE, "D:\\e", MAX_CLAIMS);

		Assertions.assertThat(reserve("worker-a")).isEmpty();
	}

	@Test
	void releasingLeavesTheAttemptCountUntouchedAndTheRowWaitingAgain() {
		Execution pending = enqueue(ExecutionType.RECONCILE, "D:\\f", 0);

		reserve("worker-a");

		Assertions.assertThat(executionQueue.release(pending.getId(), "worker-a", 10)).isTrue();

		Execution stored = executionRepository.findById(pending.getId()).orElseThrow();

		Assertions.assertThat(stored.getStatus()).isEqualTo(ExecutionStatus.PENDING);
		Assertions.assertThat(stored.getClaimedBy()).isNull();
		Assertions.assertThat(stored.getClaimCount()).isZero();
		Assertions.assertThat(stored.getAvailableAt()).isAfter(LocalDateTime.now().minusSeconds(1));
	}

	@Test
	void refusesToReleaseAnExecutionHeldByAnotherWorker() {
		Execution pending = enqueue(ExecutionType.RECONCILE, "D:\\other", 0);

		reserve("worker-a");

		Assertions.assertThat(executionQueue.release(pending.getId(), "worker-b", 10)).isFalse();
	}

	@Test
	void doesNotReserveBeforeTheBackoffHasElapsed() {
		Execution pending = enqueue(ExecutionType.RECONCILE, "D:\\g", 0);

		reserve("worker-a");

		executionQueue.release(pending.getId(), "worker-a", 3600);

		Assertions.assertThat(reserve("worker-a")).isEmpty();
	}

	@Test
	void renewsEveryLeaseTheWorkerHoldsInOneStatement() {
		Execution first = enqueue(ExecutionType.INVENTORY, "D:\\h", 0);
		Execution second = enqueue(ExecutionType.RECONCILE, "D:\\i", 0);

		reserve("worker-a");
		reserve("worker-a");

		LocalDateTime before = executionRepository.findById(first.getId()).orElseThrow().getLeaseUntil();

		Assertions.assertThat(executionQueue.renewLeases("worker-a", List.of(first.getId(), second.getId()), 600))
				.isEqualTo(2);
		Assertions.assertThat(executionRepository.findById(first.getId()).orElseThrow().getLeaseUntil())
				.isAfter(before);
	}

	/**
	 * A worker holding nothing still ticks. Asking the database to renew an empty
	 * set would be a statement with an empty array and no rows to match, so the
	 * question is answered without a round trip.
	 */
	@Test
	void renewsNothingWhenTheWorkerHoldsNoExecution() {
		Assertions.assertThat(executionQueue.renewLeases("worker-idle", List.of(), 600)).isZero();
	}

	@Test
	void doesNotRenewALeaseHeldByAnotherWorker() {
		Execution pending = enqueue(ExecutionType.RECONCILE, "D:\\j", 0);

		reserve("worker-a");

		Assertions.assertThat(executionQueue.renewLeases("worker-b", List.of(pending.getId()), 600)).isZero();
	}

	@Test
	void reportsTheExecutionsWhoseOwnerStoppedRenewing() {
		Execution abandoned = enqueue(ExecutionType.RECONCILE, "D:\\k", 1);

		abandoned.setStatus(ExecutionStatus.RUNNING);
		abandoned.setClaimedBy("worker-dead");
		abandoned.setLeaseUntil(LocalDateTime.now().minusMinutes(10));

		executionRepository.save(abandoned);

		Assertions.assertThat(executionQueue.expiredLeases()).contains(abandoned.getId());
	}

	/**
	 * The one that justifies a real database. Two claimers race for a single
	 * pending row: {@code SKIP LOCKED} must hand it to exactly one of them and let
	 * the other find nothing, rather than blocking it or handing the row twice.
	 */
	@Test
	void handsOneRowToExactlyOneOfTwoConcurrentClaimers() throws Exception {
		Execution pending = enqueue(ExecutionType.INVENTORY, "D:\\race", 0);

		Callable<Optional<ClaimedExecution>> claimer = () -> reserve("worker-" + Thread.currentThread().threadId());

		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Future<Optional<ClaimedExecution>> first = pool.submit(claimer);
			Future<Optional<ClaimedExecution>> second = pool.submit(claimer);

			List<Optional<ClaimedExecution>> results = List.of(first.get(), second.get());

			Assertions.assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
			Assertions.assertThat(results).filteredOn(Optional::isPresent).first()
					.satisfies(claimed -> Assertions.assertThat(claimed.orElseThrow().id())
							.isEqualTo(pending.getId()));
		}
	}

	/**
	 * Deduplication is the database's job, not a SELECT followed by an INSERT: two
	 * requests arriving at once would both find nothing and both insert.
	 */
	@Test
	void refusesASecondPendingRequestForTheSameTarget() {
		enqueue(ExecutionType.INVENTORY, "D:\\dedup", 0, "d:\\dedup");

		Assertions.assertThatThrownBy(() -> enqueue(ExecutionType.INVENTORY, "D:\\dedup", 0, "d:\\dedup"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	/**
	 * And the case a single index over both states would have forbidden: one scan
	 * running while the next request waits its turn, which is what replaces the
	 * old in-memory "another inventory is pending" flag.
	 */
	@Test
	void allowsOnePendingRequestWhileAnotherOfTheSameTargetRuns() {
		enqueue(ExecutionType.INVENTORY, "D:\\both", 0, "d:\\both");

		reserve("worker-a");

		Assertions.assertThatCode(() -> enqueue(ExecutionType.INVENTORY, "D:\\both", 0, "d:\\both"))
				.doesNotThrowAnyException();
	}

	@Test
	void doesNotDeduplicateTheTypesThatAcceptRepeatedRequests() {
		enqueue(ExecutionType.CONVERSION, "D:\\videos", 0, null);

		Assertions.assertThatCode(() -> enqueue(ExecutionType.CONVERSION, "D:\\videos", 0, null))
				.doesNotThrowAnyException();
	}

	/**
	 * Waiting has to be worth something, or a scheduled pass sits behind
	 * interactive work forever. An old request at priority zero outranks a fresh
	 * one at priority two.
	 */
	@Test
	void letsWaitingOutweighPriority() {
		Execution waiting = enqueue(ExecutionType.INVENTORY, "D:\\old", 0);

		waiting.setCreatedAt(LocalDateTime.now().minusHours(5));

		executionRepository.save(waiting);

		Execution fresh = enqueue(ExecutionType.RECONCILE, "D:\\new", 0);

		fresh.setPriority(2);

		executionRepository.save(fresh);

		Assertions.assertThat(reserve("worker-a").orElseThrow().id()).isEqualTo(waiting.getId());
	}

	@Test
	void recordsACancellationRequestOnARunningExecution() {
		Execution pending = enqueue(ExecutionType.RECONCILE, "D:\\cancel", 0);

		reserve("worker-a");

		Assertions.assertThat(executionQueue.requestCancel(pending.getId())).isTrue();
		Assertions.assertThat(executionQueue.isCancelRequested(pending.getId())).isTrue();
	}

	/**
	 * A request nobody has taken is cancelled by finishing it outright, and a
	 * finished one has nothing left to interrupt - so neither is a running
	 * execution to flag.
	 */
	@Test
	void refusesToCancelAnExecutionThatIsNotRunning() {
		Execution pending = enqueue(ExecutionType.RECONCILE, "D:\\waiting", 0);

		Assertions.assertThat(executionQueue.requestCancel(pending.getId())).isFalse();
		Assertions.assertThat(executionQueue.isCancelRequested(pending.getId())).isFalse();
	}

	/**
	 * Recovery's question, and the only one there is about abandonment: a lease
	 * that stopped being renewed. Asked of PostgreSQL rather than of a set in
	 * somebody's memory, which is what lets any process answer it.
	 */
	@Test
	void reportsRunningExecutionsWhoseLeaseLapsedAsAbandoned() {
		Execution lapsed = enqueue(ExecutionType.RECONCILE, "D:\\lapsed", 1);

		lapsed.setStatus(ExecutionStatus.RUNNING);
		lapsed.setClaimedBy("worker-dead");
		lapsed.setLeaseUntil(LocalDateTime.now().minusMinutes(10));

		executionRepository.save(lapsed);

		Assertions.assertThat(executionQueue.expiredLeases()).contains(lapsed.getId());
	}

	/**
	 * A claim writes status, owner and lease together, so a worker holding one is
	 * never mistaken for a dead one - by any process, without either of them
	 * having to know the other exists.
	 */
	@Test
	void doesNotReportAnExecutionWhoseLeaseIsStillValid() {
		Execution pending = enqueue(ExecutionType.RECONCILE, "D:\\owned", 0);

		reserve("worker-a");

		Assertions.assertThat(executionQueue.expiredLeases()).doesNotContain(pending.getId());

		Execution claimed = executionRepository.findById(pending.getId()).orElseThrow();

		Assertions.assertThat(claimed.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
		Assertions.assertThat(claimed.getClaimedBy()).isEqualTo("worker-a");
		Assertions.assertThat(claimed.getLeaseUntil()).isAfter(LocalDateTime.now());
	}

	/**
	 * Taking back what a dead worker left: no owner is named, because the owner is
	 * gone and demanding its name would mean nobody could ever take the work back.
	 */
	@Test
	void putsAnAbandonedExecutionBackOnTheQueue() {
		Execution queued = enqueue(ExecutionType.INVENTORY, "D:\\abandoned", 0);

		reserve("dead-worker").orElseThrow();

		Assertions.assertThat(executionQueue.requeue(queued.getId())).isTrue();

		Execution waiting = executionRepository.findById(queued.getId()).orElseThrow();

		Assertions.assertThat(waiting.getStatus()).isEqualTo(ExecutionStatus.PENDING);
		Assertions.assertThat(waiting.getClaimedBy()).isNull();
		Assertions.assertThat(waiting.getLeaseUntil()).isNull();
	}

	@Test
	void requeuesNothingThatIsNotRunning() {
		Execution queued = enqueue(ExecutionType.INVENTORY, "D:\\waiting", 0);

		Assertions.assertThat(executionQueue.requeue(queued.getId())).isFalse();
	}

	/**
	 * The case that used to raise a constraint violation from inside the recovery
	 * path. One request may wait while another of the same runs - the design says
	 * so - but two may not wait, so handing this one back is refused and the
	 * caller ends it instead.
	 */
	@Test
	void refusesToHandBackAnExecutionWhoseSuccessorIsAlreadyWaiting() {
		enqueue(ExecutionType.RECONCILE, "D:\\library", 0, "library");

		ClaimedExecution claimed = reserve("worker-a").orElseThrow();

		enqueue(ExecutionType.RECONCILE, "D:\\library", 0, "library");

		Assertions.assertThat(executionQueue.release(claimed.id(), "worker-a", 5)).isFalse();
		Assertions.assertThat(executionQueue.hasWaitingDuplicate(claimed.id())).isTrue();
		Assertions.assertThat(executionQueue.requeue(claimed.id())).isFalse();
	}

	@Test
	void handsBackAnExecutionThatHasNoSuccessorWaiting() {
		enqueue(ExecutionType.RECONCILE, "D:\\library", 0, "library");

		ClaimedExecution claimed = reserve("worker-a").orElseThrow();

		Assertions.assertThat(executionQueue.release(claimed.id(), "worker-a", 5)).isTrue();
		Assertions.assertThat(executionQueue.hasWaitingDuplicate(claimed.id())).isFalse();
	}

	/**
	 * What an administrative operation needs before it can reach a standstill:
	 * what is running is asked to stop, and what is merely waiting is ended, or a
	 * worker would take it up the moment the asking was over.
	 */
	@Test
	void asksEverythingToStopAndEndsWhatWasOnlyWaiting() {
		Execution first = enqueue(ExecutionType.INVENTORY, "D:\\first", 0);
		Execution second = enqueue(ExecutionType.RECONCILE, "D:\\second", 0);

		ClaimedExecution claimed = reserve("worker-a").orElseThrow();

		// Whichever of the two the aging order handed over is the running one; the
		// other is the one still waiting.
		long stillWaiting = claimed.id() == first.getId() ? second.getId() : first.getId();

		Assertions.assertThat(executionQueue.requestCancelOfEverything()).isEqualTo(2);

		Assertions.assertThat(executionQueue.isCancelRequested(claimed.id())).isTrue();

		Execution ended = executionRepository.findById(stillWaiting).orElseThrow();

		Assertions.assertThat(ended.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
		Assertions.assertThat(ended.getFinishedAt()).isNotNull();
	}

	@Test
	void asksNothingToStopWhenNothingIsInFlight() {
		Assertions.assertThat(executionQueue.requestCancelOfEverything()).isZero();
	}

	private Optional<ClaimedExecution> reserve(String workerId) {
		return executionQueue.reserve(workerId, List.of(ExecutionType.INVENTORY.name(), ExecutionType.RECONCILE.name()),
				MAX_CLAIMS, LEASE_SECONDS);
	}

	private Execution enqueue(ExecutionType type, String sourcePath, int claimCount) {
		return enqueue(type, sourcePath, claimCount, null);
	}

	private Execution enqueue(ExecutionType type, String sourcePath, int claimCount, String dedupKey) {
		return executionRepository.saveAndFlush(Execution.builder().executionType(type).status(ExecutionStatus.PENDING)
				.sourcePath(sourcePath).claimCount(claimCount).dedupKey(dedupKey).build());
	}
}