package br.com.jorgemelo.nimbusfilemanager.worker.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnershipGuard;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionPossession;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.ExecutionStepRepository;
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueue;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * Recovery while the application is running, and the races that only exist
 * because it does.
 *
 * <p>
 * The incident this closes: a worker was killed holding a RECONCILE whose lease
 * still had minutes on it, the replacement started before those minutes were up
 * and correctly recovered nothing, the lease then lapsed with nobody left to
 * notice, and the row stayed RUNNING. Six minutes and two restarts later a
 * start finally happened to fall on the far side of the deadline. Recovery ran
 * only at startup, so the system had no way back on its own.
 *
 * <p>
 * Against a real PostgreSQL, because every invariant here is a conditional
 * {@code UPDATE} arbitrated by the database and a mock would only agree with
 * whatever this test believed. Deliberately not {@code @Transactional}: the
 * transitions being asserted are the ones that exist only once something
 * commits, and two of these tests use two connections at once.
 *
 * <p>
 * Time is a clock the test owns, wired into the queue under test. No sleeping,
 * no waiting on a real deadline: the moment the lease lapses is placed exactly
 * where each case needs it.
 */
@SpringBootTest
@Testcontainers
class ExecutionReclaimRuntimeRecoveryIntegrationTest {

	private static final String FOLDER = "/library";

	private static final String DEAD_WORKER = "worker-that-was-killed";

	private static final int MAX_CLAIMS = 3;

	private static final int LEASE_SECONDS = 120;

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

	@Autowired
	private NamedParameterJdbcTemplate jdbcTemplate;

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private ExecutionStepRepository executionStepRepository;

	@Autowired
	private ExecutionProgressService executionProgressService;

	@Autowired
	private ExecutionEnqueueService executionEnqueueService;

	@Autowired
	private ExecutionQueryService executionQueryService;

	@Autowired
	private ExecutionOwnershipGuard executionOwnershipGuard;

	@Autowired
	private List<ExecutionJobHandler> handlers;

	private AdvanceableClock clock;
	private ExecutionQueue queue;
	private ExecutionReclaim reclaim;

	@BeforeEach
	void anEmptyQueueAndAClockOfOurOwn() {
		executionStepRepository.deleteAll();
		executionRepository.deleteAll();

		clock = new AdvanceableClock(Instant.now(), ZoneId.systemDefault());

		queue = new ExecutionQueue(jdbcTemplate, clock);
		reclaim = reclaimer();
	}

	/**
	 * The incident itself. One process, no restart between the two passes: the
	 * first is the one the start of a worker runs and it must find nothing, and
	 * the second is the one that now happens on a timer.
	 */
	@Test
	void theLeaseThatOutlivesTheStartupPassIsRecoveredByALaterOneWithoutARestart() {
		Execution orphan = executionRepository.saveAndFlush(runningWithLeaseIn(Duration.ofMinutes(2)));

		assertThat(reclaim.reclaimAbandoned()).as("its owner still had time left, so there was nothing to take back")
				.isZero();
		assertThat(reload(orphan).getStatus()).isEqualTo(ExecutionStatus.RUNNING);

		clock.advance(Duration.ofMinutes(3));

		assertThat(reclaim.reclaimAbandoned()).as("the lease has now lapsed and the same instance notices").isOne();

		Execution recovered = reload(orphan);

		assertThat(recovered.getStatus()).isEqualTo(ExecutionStatus.PENDING);
		assertThat(recovered.getClaimedBy()).isNull();
		assertThat(recovered.getLeaseUntil()).isNull();
	}

	/**
	 * Recovering is not the point; progressing is. The row has to be claimable by
	 * somebody else afterwards, to survive the guard that decides whether an
	 * attempt may start, and to reach a terminal state under its new owner.
	 */
	@Test
	void theRecoveredExecutionIsTakenByAnotherWorkerAndRunsThroughToTheEnd() {
		Execution orphan = executionRepository.saveAndFlush(runningWithLeaseIn(Duration.ofMinutes(2)));

		clock.advance(Duration.ofMinutes(3));

		assertThat(reclaim.reclaimAbandoned()).isOne();

		Optional<ClaimedExecution> taken = claimAs("worker-that-took-over");

		assertThat(taken).get().extracting(ClaimedExecution::id).isEqualTo(orphan.getId());
		assertThat(queue.countAttempt(orphan.getId(), "worker-that-took-over", MAX_CLAIMS))
				.as("the new owner is allowed to start for real").isPresent();

		executionProgressService.finishReconcile(Takings.owning(orphan.getId()), 10, 0,
				ExecutionMessages.reconcileRepaired(0, 0, 0));

		assertThat(reload(orphan).getStatus()).isEqualTo(ExecutionStatus.FINISHED);
	}

	/**
	 * A worker that is alive keeps its work. The pass runs again and again while
	 * the lease is renewed ahead of every one of them, and takes nothing.
	 */
	@Test
	void anOwnerThatKeepsRenewingIsNeverInterruptedByRecovery() {
		Execution alive = executionRepository.saveAndFlush(runningWithLeaseIn(Duration.ofMinutes(2)));

		for (int round = 0; round < 3; round++) {
			assertThat(reclaim.reclaimAbandoned()).isZero();

			clock.advance(Duration.ofMinutes(1));

			assertThat(queue.renewLeases(DEAD_WORKER, List.of(takingOf(alive)), LEASE_SECONDS)).hasSize(1);
		}

		Execution untouched = reload(alive);

		assertThat(untouched.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
		assertThat(untouched.getClaimedBy()).isEqualTo(DEAD_WORKER);
	}

	/**
	 * Race A, which used to be the reason this slice was more than a scheduler and
	 * is now impossible to construct.
	 *
	 * <p>
	 * The race was: recovery reads the abandoned rows, and before it acts on one
	 * of them the owner - paused rather than dead - renews and carries on. That
	 * needed a lapsed taking to be able to renew, and it cannot: recovery selects
	 * on {@code lease_until < now} and renewal requires {@code lease_until >= now},
	 * so a row read as abandoned stays abandoned however late its owner speaks.
	 * The requeue still carries the lease condition, for the case this one no
	 * longer covers - another reclaimer putting the row back and somebody claiming
	 * it afresh between the reading and the write.
	 */
	@Test
	void aTakingReadAsAbandonedCannotRenewItselfBackFromUnderRecovery() {
		Execution paused = executionRepository.saveAndFlush(runningWithLeaseIn(Duration.ofMinutes(2)));

		clock.advance(Duration.ofMinutes(3));

		assertThat(queue.expiredLeases()).as("recovery has read it as abandoned").containsExactly(paused.getId());

		assertThat(queue.renewLeases(DEAD_WORKER, List.of(takingOf(paused)), LEASE_SECONDS))
				.as("and its owner speaking now changes nothing").isEmpty();

		assertThat(queue.requeue(paused.getId())).as("so recovery still takes it back").isTrue();

		Execution recovered = reload(paused);

		assertThat(recovered.getStatus()).isEqualTo(ExecutionStatus.PENDING);
		assertThat(recovered.getClaimedBy()).isNull();
	}

	/**
	 * Race C, and the one that touches the supersession rule. The owner finishes
	 * after its lease lapsed and before recovery got to the row. A successor is
	 * waiting, so the successor check says yes - and that used to be enough to
	 * write a refusal over a completed execution. Two conditions now, and the
	 * completion stands.
	 */
	@Test
	void aCompletionThatLandsFirstIsNotRewrittenAsSuperseded() {
		Execution finishing = executionRepository.saveAndFlush(runningWithLeaseIn(Duration.ofMinutes(2)));

		executionRepository.saveAndFlush(waitingSuccessor());

		clock.advance(Duration.ofMinutes(3));

		assertThat(queue.expiredLeases()).containsExactly(finishing.getId());

		executionProgressService.finishReconcile(Takings.owning(finishing.getId()), 10, 0,
				ExecutionMessages.reconcileRepaired(0, 0, 0));

		assertThat(reclaim.reclaimAbandoned()).isZero();

		assertThat(queue.hasWaitingDuplicate(finishing.getId()))
				.as("a successor really is waiting, which alone used to be read as supersession").isTrue();
		assertThat(reload(finishing).getStatus()).isEqualTo(ExecutionStatus.FINISHED);
	}

	/**
	 * Race E. A cancellation is somebody's decision and recovery is a repair;
	 * the repair must not undo the decision.
	 */
	@Test
	void aCancellationThatLandsFirstIsNotUndone() {
		Execution cancelled = executionRepository.saveAndFlush(runningWithLeaseIn(Duration.ofMinutes(2)));

		clock.advance(Duration.ofMinutes(3));

		assertThat(queue.expiredLeases()).containsExactly(cancelled.getId());

		executionProgressService.cancel(Takings.owning(cancelled.getId()), ExecutionMessages.inventoryCancelled());

		assertThat(reclaim.reclaimAbandoned()).isZero();
		assertThat(reload(cancelled).getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
	}

	/**
	 * Race D, and the multi-instance case in the same test: two reclaimers of
	 * their own, as two Nimbus processes would have, released together onto one
	 * abandoned row. Exactly one may report having taken it, and the row must end
	 * up waiting once rather than twice.
	 */
	@Test
	void twoReclaimersReleasedTogetherRecoverTheRowExactlyOnce() throws Exception {
		Execution orphan = executionRepository.saveAndFlush(runningWithLeaseIn(Duration.ofMinutes(2)));

		clock.advance(Duration.ofMinutes(3));

		CyclicBarrier together = new CyclicBarrier(2);

		ExecutorService reclaimers = Executors.newFixedThreadPool(2);

		try {
			ExecutionReclaim first = reclaimer();
			ExecutionReclaim second = reclaimer();

			Future<Integer> one = reclaimers.submit(() -> bothAtOnce(together, first));
			Future<Integer> other = reclaimers.submit(() -> bothAtOnce(together, second));

			assertThat(valueOf(one) + valueOf(other)).as("the database arbitrates; the losing pass writes nothing")
					.isOne();
		} finally {
			reclaimers.shutdownNow();
		}

		assertThat(reload(orphan).getStatus()).isEqualTo(ExecutionStatus.PENDING);
		assertThat(executionRepository.findAll()).hasSize(1);
	}

	/**
	 * What the incident looked like from the outside, and why it was noticed at
	 * all: an orphan counts as an active execution, which is what tells the rest
	 * of the product the system is busy. Recovery does not make it stop counting -
	 * a recovered row is waiting, and waiting is still active - it makes it able
	 * to finish, which is the difference between busy and stuck.
	 */
	@Test
	void anOrphanKeepsTheSystemLookingBusyUntilRecoveryLetsItFinish() {
		Execution orphan = executionRepository.saveAndFlush(runningWithLeaseIn(Duration.ofMinutes(2)));

		assertThat(executionQueryService.active()).as("this is what blocked the watcher").isPresent();

		clock.advance(Duration.ofMinutes(3));

		assertThat(reclaim.reclaimAbandoned()).isOne();

		claimAs("worker-that-took-over");

		executionProgressService.finishReconcile(Takings.owning(orphan.getId()), 10, 0,
				ExecutionMessages.reconcileRepaired(0, 0, 0));

		assertThat(executionQueryService.active()).as("the system is free again without anybody restarting it")
				.isEmpty();
	}

	/**
	 * The whole point of the possession, in one sequence: the owner comes back
	 * after its row was recovered and taken by somebody else, and every kind of
	 * write it still has in hand lands on nothing. The new owner then finishes
	 * normally, which is the half that proves the guard blocks the late writer
	 * rather than the row.
	 */
	@Test
	void nothingTheLostOwnerWritesReachesTheRowItsSuccessorNowHolds() {
		Execution orphan = executionRepository.saveAndFlush(runningWithLeaseIn(Duration.ofMinutes(2)));

		takenBy(orphan.getId(), DEAD_WORKER, 1);

		clock.advance(Duration.ofMinutes(3));

		assertThat(reclaim.reclaimAbandoned()).isOne();

		claimAs("worker-that-took-over");

		int successorAttempt = queue.countAttempt(orphan.getId(), "worker-that-took-over", MAX_CLAIMS).orElseThrow();

		assertThat(successorAttempt).as("a new taking, and a number that says so").isEqualTo(2);

		takenBy(orphan.getId(), "worker-that-took-over", successorAttempt);

		ExecutionOwnership stale = Takings.taking(orphan.getId(), 1, executionOwnershipGuard);

		Execution untouched = reload(orphan);

		executionProgressService.updateLiveProgress(stale, 99, 99, 0, 0, ExecutionMessages.processingFiles());
		executionProgressService.updatePhase(stale, ExecutionPhase.SCANNING, ExecutionStepType.STARTED,
				ExecutionMessages.processingFiles());
		executionProgressService.updateTotal(stale, 999);
		executionProgressService.finish(stale, ExecutionStatus.FINISHED, 99, 99, 0, 0,
				ExecutionMessages.inventoryCompleted());
		executionProgressService.fail(stale, ExecutionMessages.inventoryFailed("too late"));

		assertThat(queue.release(orphan.getId(), DEAD_WORKER, OptionalInt.of(1),
				0)).as("and it cannot hand back a row that is not its own").isFalse();

		Execution held = reload(orphan);

		assertThat(held.getStatus()).as("still running, under its new owner").isEqualTo(ExecutionStatus.RUNNING);
		assertThat(held.getClaimedBy()).isEqualTo("worker-that-took-over");
		assertThat(held.getFinishedAt()).as("nothing ended it").isNull();
		assertThat(held.getFilesFound()).as("and no counter of the lost owner's landed")
				.isEqualTo(untouched.getFilesFound());
		assertThat(held.getTotalExpected()).isEqualTo(untouched.getTotalExpected());
		assertThat(held.getPhase()).isEqualTo(untouched.getPhase());

		executionOwnershipGuard.takes(new ExecutionPossession(orphan.getId(), "worker-that-took-over",
				successorAttempt));

		executionProgressService.finishReconcile(Takings.owning(orphan.getId()), 10, 0,
				ExecutionMessages.reconcileRepaired(0, 0, 0));

		assertThat(reload(orphan).getStatus()).as("the new owner ends it normally")
				.isEqualTo(ExecutionStatus.FINISHED);
	}

	/**
	 * The case a worker name alone cannot answer. The same worker loses the row
	 * and claims it again, so {@code claimed_by} reads exactly as it did before -
	 * and a write left over from the first taking must still land on nothing. The
	 * attempt number is what tells the two apart.
	 */
	@Test
	void aWriteFromAnEarlierTakingIsRefusedEvenWhenTheSameWorkerHoldsItAgain() {
		Execution orphan = executionRepository.saveAndFlush(runningWithLeaseIn(Duration.ofMinutes(2)));

		takenBy(orphan.getId(), DEAD_WORKER, 1);

		clock.advance(Duration.ofMinutes(3));

		assertThat(reclaim.reclaimAbandoned()).isOne();

		claimAs(DEAD_WORKER);

		assertThat(queue.countAttempt(orphan.getId(), DEAD_WORKER, MAX_CLAIMS)).hasValue(2);

		takenBy(orphan.getId(), DEAD_WORKER, 2);

		executionProgressService.finish(Takings.taking(orphan.getId(), 1, executionOwnershipGuard),
				ExecutionStatus.FINISHED, 99, 99, 0, 0, ExecutionMessages.inventoryCompleted());

		Execution held = reload(orphan);

		assertThat(held.getStatus()).as("the first taking cannot finish what the second one is running")
				.isEqualTo(ExecutionStatus.RUNNING);
		assertThat(held.getFilesFound()).isNotEqualTo(99);

		assertThat(queue.release(orphan.getId(), DEAD_WORKER, OptionalInt.of(1), 0))
				.as("nor hand it back").isFalse();
	}

	/**
	 * Registers what a worker took, the way the dispatcher does once it has
	 * counted the attempt and the number exists.
	 */
	private void takenBy(long executionId, String workerId, int claimCount) {
		executionOwnershipGuard.takes(new ExecutionPossession(executionId, workerId, claimCount));
	}

	private int bothAtOnce(CyclicBarrier together, ExecutionReclaim reclaimer) throws Exception {
		together.await(10, TimeUnit.SECONDS);

		return reclaimer.reclaimAbandoned();
	}

	private int valueOf(Future<Integer> pass) throws InterruptedException, ExecutionException, TimeoutException {
		return pass.get(20, TimeUnit.SECONDS);
	}

	/**
	 * The taking that was replaced is refused by the row, and the one that
	 * replaced it is not - with the same worker name on both, so nothing but the
	 * attempt number can be telling them apart.
	 */
	@Test
	void thePinRefusesTheReplacedTakingAndAcceptsTheOneThatReplacedIt() {
		Execution orphan = executionRepository.saveAndFlush(runningWithLeaseIn(Duration.ofMinutes(2)));

		ExecutionOwnershipGuard guard = new ExecutionOwnershipGuard(queue);

		guard.takes(new ExecutionPossession(orphan.getId(), DEAD_WORKER, 1));

		clock.advance(Duration.ofMinutes(3));

		assertThat(reclaim.reclaimAbandoned()).isOne();

		claimAs(DEAD_WORKER);

		int later = queue.countAttempt(orphan.getId(), DEAD_WORKER, MAX_CLAIMS).orElseThrow();

		guard.takes(new ExecutionPossession(orphan.getId(), DEAD_WORKER, later));

		assertThat(guard.pin(orphan.getId(), 1)).as("the row is at attempt %d and says no to 1", later).isFalse();
		assertThat(guard.pin(orphan.getId(), later)).isTrue();
	}

	/**
	 * A heartbeat belonging to the taking that ended cannot extend the one that
	 * replaced it. Both assertions matter: that the renewal does not report the
	 * old taking as renewed, and that the row of the new one was not quietly
	 * touched on the way past.
	 */
	@Test
	void aHeartbeatForTheReplacedTakingLeavesTheLaterOnesLeaseAlone() {
		Execution orphan = executionRepository.saveAndFlush(runningWithLeaseIn(Duration.ofMinutes(2)));

		clock.advance(Duration.ofMinutes(3));

		assertThat(reclaim.reclaimAbandoned()).isOne();

		claimAs(DEAD_WORKER);

		int later = queue.countAttempt(orphan.getId(), DEAD_WORKER, MAX_CLAIMS).orElseThrow();

		LocalDateTime leaseOfTheLaterTaking = reload(orphan).getLeaseUntil();

		assertThat(queue.renewLeases(DEAD_WORKER, List.of(new ExecutionPossession(orphan.getId(), DEAD_WORKER, 1)),
				LEASE_SECONDS)).as("the replaced taking renews nothing").isEmpty();
		assertThat(reload(orphan).getLeaseUntil()).as("and did not touch the later taking's row")
				.isEqualTo(leaseOfTheLaterTaking);

		clock.advance(Duration.ofSeconds(1));

		assertThat(queue.renewLeases(DEAD_WORKER,
				List.of(new ExecutionPossession(orphan.getId(), DEAD_WORKER, later)), LEASE_SECONDS))
				.as("while its own heartbeat still works").hasSize(1);
		assertThat(reload(orphan).getLeaseUntil()).isAfter(leaseOfTheLaterTaking);
	}

	/**
	 * And the whole of it: the replaced taking goes on doing its late lifecycle
	 * housekeeping, none of it reaches the taking that replaced it, and that one
	 * runs through to a terminal state as if the other had never been there.
	 */
	@Test
	void theLaterTakingReachesItsEndDespiteTheEarlierOnesLateHousekeeping() {
		Execution orphan = executionRepository.saveAndFlush(runningWithLeaseIn(Duration.ofMinutes(2)));

		ExecutionOwnershipGuard guard = new ExecutionOwnershipGuard(queue);

		ExecutionPossession earlier = new ExecutionPossession(orphan.getId(), DEAD_WORKER, 1);

		guard.takes(earlier);

		clock.advance(Duration.ofMinutes(3));

		assertThat(reclaim.reclaimAbandoned()).isOne();

		claimAs(DEAD_WORKER);

		int later = queue.countAttempt(orphan.getId(), DEAD_WORKER, MAX_CLAIMS).orElseThrow();

		guard.takes(new ExecutionPossession(orphan.getId(), DEAD_WORKER, later));

		// Everything the taking that ended still had in hand.
		guard.renewalConfirmed(List.of(earlier), List.of());
		guard.releases(orphan.getId(), 1);

		assertThat(guard.isTheCurrentTaking(orphan.getId(), later)).as("none of it reached the live taking").isTrue();
		assertThat(guard.pin(orphan.getId(), later)).isTrue();

		executionProgressService.finishReconcile(Takings.owning(orphan.getId()), 10, 0,
				ExecutionMessages.reconcileRepaired(0, 0, 0));

		assertThat(reload(orphan).getStatus()).isEqualTo(ExecutionStatus.FINISHED);
	}

	private ExecutionPossession takingOf(Execution execution) {
		return new ExecutionPossession(execution.getId(), DEAD_WORKER, reload(execution).getClaimCount());
	}

	private ExecutionReclaim reclaimer() {
		return new ExecutionReclaim(queue, executionRepository, executionProgressService, executionEnqueueService,
				new WorkerProperties(null, null, null, null, null, null, null, null, null, null), handlers);
	}

	private Optional<ClaimedExecution> claimAs(String workerId) {
		return queue.reserve(workerId, List.of(ExecutionType.RECONCILE.name()), MAX_CLAIMS, LEASE_SECONDS);
	}

	private Execution reload(Execution execution) {
		return executionRepository.findById(execution.getId()).orElseThrow();
	}

	/**
	 * The row a killed worker leaves: RUNNING, owned, and with a lease that has
	 * not run out yet - which is exactly why the startup pass could not help.
	 */
	private Execution runningWithLeaseIn(Duration remaining) {
		LocalDateTime now = LocalDateTime.now(clock);

		return Execution.builder().executionType(ExecutionType.RECONCILE).status(ExecutionStatus.RUNNING)
				.sourcePath(FOLDER).dedupKey(FOLDER).claimedBy(DEAD_WORKER).claimedAt(now.minusMinutes(1))
				.leaseUntil(now.plus(remaining)).claimCount(1).recursive(true).executeFlag(true).build();
	}

	private Execution waitingSuccessor() {
		return Execution.builder().executionType(ExecutionType.RECONCILE).status(ExecutionStatus.PENDING)
				.sourcePath(FOLDER).dedupKey(FOLDER).claimCount(0).recursive(true).executeFlag(true)
				.availableAt(LocalDateTime.now(clock)).build();
	}
}