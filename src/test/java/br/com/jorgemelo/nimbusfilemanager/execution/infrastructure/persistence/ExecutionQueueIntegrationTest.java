package br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
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

import br.com.jorgemelo.nimbusfilemanager.shared.TestPostgres;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionPossession;
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
	static PostgreSQLContainer<?> postgres = TestPostgres.container();

	/**
	 * The clock the application writes with, so what this test compares against is
	 * in the same frame as what production stored. {@code LocalDateTime.now()} reads
	 * the JVM's default zone while the row was written in the configured one, and
	 * on any machine where the two differ - every CI runner - a fresh lease looked
	 * hours expired and an expired one looked fresh.
	 */
	@Autowired
	private Clock clock;

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

		Assertions.assertThat(executionQueue.countAttempt(pending.getId(), "worker-a", MAX_CLAIMS)).isPresent();
		Assertions.assertThat(executionRepository.findById(pending.getId()).orElseThrow().getClaimCount()).isEqualTo(1);
	}

	@Test
	void refusesToChargeAnAttemptForAnotherWorker() {
		Execution pending = enqueue(ExecutionType.RECONCILE, "D:\\c", 0);

		reserve("worker-a");

		Assertions.assertThat(executionQueue.countAttempt(pending.getId(), "worker-b", MAX_CLAIMS)).isEmpty();
		Assertions.assertThat(executionRepository.findById(pending.getId()).orElseThrow().getClaimCount()).isZero();
	}

	@Test
	void refusesToChargeAnAttemptOnceTheBudgetIsSpent() {
		Execution poison = enqueue(ExecutionType.RECONCILE, "D:\\d", MAX_CLAIMS);

		poison.setStatus(ExecutionStatus.RUNNING);
		poison.setClaimedBy("worker-a");

		executionRepository.save(poison);

		Assertions.assertThat(executionQueue.countAttempt(poison.getId(), "worker-a", MAX_CLAIMS)).isEmpty();
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

		Assertions.assertThat(executionQueue.release(pending.getId(), "worker-a", OptionalInt.empty(), 10)).isTrue();

		Execution stored = executionRepository.findById(pending.getId()).orElseThrow();

		Assertions.assertThat(stored.getStatus()).isEqualTo(ExecutionStatus.PENDING);
		Assertions.assertThat(stored.getClaimedBy()).isNull();
		Assertions.assertThat(stored.getClaimCount()).isZero();
		Assertions.assertThat(stored.getAvailableAt()).isAfter(LocalDateTime.now(clock).minusSeconds(1));
	}

	@Test
	void refusesToReleaseAnExecutionHeldByAnotherWorker() {
		Execution pending = enqueue(ExecutionType.RECONCILE, "D:\\other", 0);

		reserve("worker-a");

		Assertions.assertThat(executionQueue.release(pending.getId(), "worker-b", OptionalInt.empty(), 10)).isFalse();
	}

	@Test
	void doesNotReserveBeforeTheBackoffHasElapsed() {
		Execution pending = enqueue(ExecutionType.RECONCILE, "D:\\g", 0);

		reserve("worker-a");

		executionQueue.release(pending.getId(), "worker-a", OptionalInt.empty(), 3600);

		Assertions.assertThat(reserve("worker-a")).isEmpty();
	}

	@Test
	void renewsEveryLeaseTheWorkerHoldsInOneStatement() {
		Execution first = enqueue(ExecutionType.INVENTORY, "D:\\h", 0);
		Execution second = enqueue(ExecutionType.RECONCILE, "D:\\i", 0);

		reserve("worker-a");
		reserve("worker-a");

		LocalDateTime before = executionRepository.findById(first.getId()).orElseThrow().getLeaseUntil();

		Assertions.assertThat(executionQueue.renewLeases("worker-a",
				List.of(taking(first, "worker-a"), taking(second, "worker-a")), 600))
				.extracting(ExecutionPossession::executionId)
				.containsExactlyInAnyOrder(first.getId(), second.getId());
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
		Assertions.assertThat(executionQueue.renewLeases("worker-idle", List.of(), 600)).isEmpty();
	}

	@Test
	void doesNotRenewALeaseHeldByAnotherWorker() {
		Execution pending = enqueue(ExecutionType.RECONCILE, "D:\\j", 0);

		reserve("worker-a");

		Assertions.assertThat(executionQueue.renewLeases("worker-b", List.of(taking(pending, "worker-b")), 600))
				.isEmpty();
	}

	@Test
	void reportsTheExecutionsWhoseOwnerStoppedRenewing() {
		Execution abandoned = enqueue(ExecutionType.RECONCILE, "D:\\k", 1);

		abandoned.setStatus(ExecutionStatus.RUNNING);
		abandoned.setClaimedBy("worker-dead");
		abandoned.setLeaseUntil(LocalDateTime.now(clock).minusMinutes(10));

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

		waiting.setCreatedAt(LocalDateTime.now(clock).minusHours(5));

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
		lapsed.setLeaseUntil(LocalDateTime.now(clock).minusMinutes(10));

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
		Assertions.assertThat(claimed.getLeaseUntil()).isAfter(LocalDateTime.now(clock));
	}

	/**
	 * Taking back what a dead worker left: no owner is named, because the owner is
	 * gone and demanding its name would mean nobody could ever take the work back.
	 * What is named instead is the lease, which is the thing that says it is gone.
	 */
	@Test
	void putsAnAbandonedExecutionBackOnTheQueue() {
		Execution queued = enqueue(ExecutionType.INVENTORY, "D:\\abandoned", 0);

		reserve("dead-worker", -1).orElseThrow();

		Assertions.assertThat(executionQueue.requeue(queued.getId())).isTrue();

		Execution waiting = executionRepository.findById(queued.getId()).orElseThrow();

		Assertions.assertThat(waiting.getStatus()).isEqualTo(ExecutionStatus.PENDING);
		Assertions.assertThat(waiting.getClaimedBy()).isNull();
		Assertions.assertThat(waiting.getLeaseUntil()).isNull();
	}

	/**
	 * And refuses to take it back while its owner is still there. Recovery reads
	 * the abandoned rows and then acts on them one at a time, so a worker that was
	 * paused rather than dead can renew in between - the requeue has to carry the
	 * lease condition itself, not trust the reading that led to it.
	 */
	@Test
	void refusesToRequeueAnExecutionWhoseLeaseIsStillAlive() {
		Execution queued = enqueue(ExecutionType.INVENTORY, "D:\\alive", 0);

		reserve("worker-a").orElseThrow();

		Assertions.assertThat(executionQueue.requeue(queued.getId())).isFalse();

		Execution untouched = executionRepository.findById(queued.getId()).orElseThrow();

		Assertions.assertThat(untouched.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
		Assertions.assertThat(untouched.getClaimedBy()).isEqualTo("worker-a");
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

		Assertions.assertThat(executionQueue.release(claimed.id(), "worker-a", OptionalInt.empty(), 5)).isFalse();
		Assertions.assertThat(executionQueue.hasWaitingDuplicate(claimed.id())).isTrue();
		Assertions.assertThat(executionQueue.requeue(claimed.id())).isFalse();
	}

	@Test
	void handsBackAnExecutionThatHasNoSuccessorWaiting() {
		enqueue(ExecutionType.RECONCILE, "D:\\library", 0, "library");

		ClaimedExecution claimed = reserve("worker-a").orElseThrow();

		Assertions.assertThat(executionQueue.release(claimed.id(), "worker-a", OptionalInt.empty(), 5)).isTrue();
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

	/**
	 * The taking as the row currently records it - reserving does not charge an
	 * attempt, so a freshly claimed row is taking zero.
	 */
	private ExecutionPossession taking(Execution execution, String workerId) {
		return new ExecutionPossession(execution.getId(), workerId,
				executionRepository.findById(execution.getId()).orElseThrow().getClaimCount());
	}

	// ----------------------------------------------------------------
	// Photo and video fingerprints never run at once, and photos go first
	// ----------------------------------------------------------------

	/** Both waiting: the photo is taken and the video is not offered at all. */
	@Test
	void aPhotoFingerprintIsAdmittedBeforeAVideoOneWhenBothAreWaiting() {
		enqueue(ExecutionType.FINGERPRINT_VIDEO, "d:\\library", 0, "video");
		enqueue(ExecutionType.FINGERPRINT_PHOTO, "d:\\library", 0, "photo");

		Assertions.assertThat(reserveFingerprints("worker-a")).get()
				.extracting(ClaimedExecution::executionType).isEqualTo(ExecutionType.FINGERPRINT_PHOTO.name());

		Assertions.assertThat(reserveFingerprints("worker-b")).as("the video is not admissible while a photo run is")
				.isEmpty();
	}

	/** The video keeps waiting for as long as the photo run holds the machine. */
	@Test
	void aVideoFingerprintWaitsWhileAPhotoOneIsRunning() {
		Execution video = enqueue(ExecutionType.FINGERPRINT_VIDEO, "d:\\library", 0, "video");

		enqueue(ExecutionType.FINGERPRINT_PHOTO, "d:\\library", 0, "photo");

		reserveFingerprints("worker-a");

		Assertions.assertThat(reserveFingerprints("worker-b")).isEmpty();
		Assertions.assertThat(executionRepository.findById(video.getId()).orElseThrow().getStatus())
				.isEqualTo(ExecutionStatus.PENDING);
	}

	/** And is admitted as soon as the photo run ends - no restart involved. */
	@Test
	void aVideoFingerprintBecomesAdmissibleWhenThePhotoRunEnds() {
		enqueue(ExecutionType.FINGERPRINT_VIDEO, "d:\\library", 0, "video");

		Execution photo = enqueue(ExecutionType.FINGERPRINT_PHOTO, "d:\\library", 0, "photo");

		reserveFingerprints("worker-a");

		finish(photo);

		Assertions.assertThat(reserveFingerprints("worker-b")).get().extracting(ClaimedExecution::executionType)
				.isEqualTo(ExecutionType.FINGERPRINT_VIDEO.name());
	}

	/** With no photo work anywhere, a video starts immediately. */
	@Test
	void aVideoFingerprintStartsAtOnceWhenNoPhotoRunIsWaitingOrRunning() {
		enqueue(ExecutionType.FINGERPRINT_VIDEO, "d:\\library", 0, "video");

		Assertions.assertThat(reserveFingerprints("worker-a")).get().extracting(ClaimedExecution::executionType)
				.isEqualTo(ExecutionType.FINGERPRINT_VIDEO.name());
	}

	/**
	 * The other direction, and the reason it is exclusion rather than a one-sided
	 * rule: a photo arriving mid-video waits instead of joining it. Nothing is
	 * cancelled - the video that is already running keeps running.
	 */
	@Test
	void aPhotoFingerprintArrivingDuringAVideoRunWaitsRatherThanJoiningIt() {
		Execution video = enqueue(ExecutionType.FINGERPRINT_VIDEO, "d:\\library", 0, "video");

		reserveFingerprints("worker-a");

		Execution photo = enqueue(ExecutionType.FINGERPRINT_PHOTO, "d:\\library", 0, "photo");

		Assertions.assertThat(reserveFingerprints("worker-b")).as("admission waits; it never preempts").isEmpty();

		Assertions.assertThat(executionRepository.findById(video.getId()).orElseThrow().getStatus())
				.as("the running video is untouched").isEqualTo(ExecutionStatus.RUNNING);
		Assertions.assertThat(executionRepository.findById(photo.getId()).orElseThrow().getStatus())
				.isEqualTo(ExecutionStatus.PENDING);
	}

	/** And when that video ends, the photo goes ahead of any newer video. */
	@Test
	void thePhotoThatWaitedGoesAheadOfTheNextVideo() {
		Execution video = enqueue(ExecutionType.FINGERPRINT_VIDEO, "d:\\library", 0, "video");

		reserveFingerprints("worker-a");

		enqueue(ExecutionType.FINGERPRINT_PHOTO, "d:\\library", 0, "photo");

		finish(video);

		enqueue(ExecutionType.FINGERPRINT_VIDEO, "d:\\other", 0, "video-2");

		Assertions.assertThat(reserveFingerprints("worker-b")).get().extracting(ClaimedExecution::executionType)
				.isEqualTo(ExecutionType.FINGERPRINT_PHOTO.name());
	}

	/**
	 * The policy is a property of the queue, not of a worker's memory, so a
	 * reclaimed video - back to PENDING after its owner died - is still held while
	 * a photo waits.
	 */
	@Test
	void aReclaimedVideoIsStillHeldBehindAWaitingPhoto() {
		enqueue(ExecutionType.FINGERPRINT_VIDEO, "d:\\library", 0, "video");

		reserveFingerprints("gone", -1);

		List<Long> abandoned = executionQueue.expiredLeases();

		Assertions.assertThat(abandoned).as("the owner's lease had already run out").isNotEmpty();

		abandoned.forEach(executionQueue::requeue);

		enqueue(ExecutionType.FINGERPRINT_PHOTO, "d:\\library", 0, "photo");

		Assertions.assertThat(reserveFingerprints("worker-b")).get().extracting(ClaimedExecution::executionType)
				.isEqualTo(ExecutionType.FINGERPRINT_PHOTO.name());
	}

	/**
	 * The exclusion is between those two and nothing else. An unrelated type runs
	 * beside a fingerprint exactly as it did before - this changes which work may
	 * start, never how much of it one kind runs at a time.
	 */
	@Test
	void theExclusionDoesNotReachAnyOtherKindOfWork() {
		enqueue(ExecutionType.FINGERPRINT_PHOTO, "d:\\library", 0, "photo");
		enqueue(ExecutionType.INVENTORY, "d:\\library", 0, "inventory");

		Assertions.assertThat(reserveFingerprints("worker-a")).get().extracting(ClaimedExecution::executionType)
				.isEqualTo(ExecutionType.FINGERPRINT_PHOTO.name());

		Assertions.assertThat(reserve("worker-b")).as("an inventory is not held back by a fingerprint")
				.isPresent();
	}

	/**
	 * The 1 + 1 rule is a different {@code NOT EXISTS} and keeps working: a second
	 * photo with the same key waits for the running one, which is refused here for
	 * that reason rather than the new one.
	 */
	@Test
	void theOneAndOneRuleStillHoldsWithinEachFingerprintType() {
		enqueue(ExecutionType.FINGERPRINT_PHOTO, "d:\\library", 0, "photo");

		reserveFingerprints("worker-a");

		enqueue(ExecutionType.FINGERPRINT_PHOTO, "d:\\library", 0, "photo");

		Assertions.assertThat(reserveFingerprints("worker-b")).as("same key, already running").isEmpty();
	}

	private void finish(Execution execution) {
		Execution stored = executionRepository.findById(execution.getId()).orElseThrow();

		stored.setStatus(ExecutionStatus.FINISHED);

		executionRepository.saveAndFlush(stored);
	}

	private Optional<ClaimedExecution> reserveFingerprints(String workerId) {
		return reserveFingerprints(workerId, LEASE_SECONDS);
	}

	private Optional<ClaimedExecution> reserveFingerprints(String workerId, int leaseSeconds) {
		return executionQueue.reserve(workerId,
				List.of(ExecutionType.FINGERPRINT_PHOTO.name(), ExecutionType.FINGERPRINT_VIDEO.name()), MAX_CLAIMS,
				leaseSeconds);
	}

	private Optional<ClaimedExecution> reserve(String workerId) {
		return reserve(workerId, LEASE_SECONDS);
	}

	/**
	 * A negative lease is how a test says "this one was taken by a worker that is
	 * already gone" without waiting for a real one to run out.
	 */
	private Optional<ClaimedExecution> reserve(String workerId, int leaseSeconds) {
		return executionQueue.reserve(workerId, List.of(ExecutionType.INVENTORY.name(), ExecutionType.RECONCILE.name()),
				MAX_CLAIMS, leaseSeconds);
	}

	/**
	 * Handing a row back throws away the window its estimate was measured over.
	 *
	 * <p>
	 * The measurement belongs to the attempt that took it, and the next attempt
	 * does the work again from wherever the drain now stands. Carrying the marks
	 * across would divide a fresh count by an old moment - the estimate would come
	 * out of a rate that describes work being repeated - and, worse, it would do so
	 * silently, since the numbers stay plausible.
	 *
	 * <p>
	 * Asserted against the database because the clearing is in the UPDATE and
	 * nowhere else: the Java that opens the window never runs on this path.
	 */
	@Test
	void releasingThrowsAwayTheWindowTheEstimateWasMeasuredOver() {
		Execution pending = measured(enqueue(ExecutionType.RECONCILE, "D:\f", 0));

		reserve("worker-a");

		Assertions.assertThat(executionQueue.release(pending.getId(), "worker-a", OptionalInt.empty(), 10)).isTrue();

		assertNoWindow(pending.getId());
	}

	/** And so does a reclaim, for the same reason and by the same statement. */
	@Test
	void requeuingThrowsAwayTheWindowToo() {
		Execution queued = measured(enqueue(ExecutionType.RECONCILE, "D:\f", 0));

		reserve("dead-worker", -1).orElseThrow();

		Assertions.assertThat(executionQueue.requeue(queued.getId())).isTrue();

		assertNoWindow(queued.getId());
	}

	/** A row that had got somewhere and had a window open over it. */
	private Execution measured(Execution execution) {
		execution.setFilesAnalyzed(500);
		execution.setTotalExpected(10_000);
		execution.setRateWindowFromAt(LocalDateTime.now(clock).minusMinutes(5));
		execution.setRateWindowFromDone(100);
		execution.setRateWindowMarkAt(LocalDateTime.now(clock).minusMinutes(1));
		execution.setRateWindowMarkDone(400);

		return executionRepository.saveAndFlush(execution);
	}

	private void assertNoWindow(long executionId) {
		Execution stored = executionRepository.findById(executionId).orElseThrow();

		Assertions.assertThat(stored.getStartedAt()).as("the clock is reset with the measurement").isNull();
		Assertions.assertThat(stored.getRateWindowFromAt()).isNull();
		Assertions.assertThat(stored.getRateWindowFromDone()).isNull();
		Assertions.assertThat(stored.getRateWindowMarkAt()).isNull();
		Assertions.assertThat(stored.getRateWindowMarkDone()).isNull();

		Assertions.assertThat(stored.getFilesAnalyzed()).as("the counters are the next attempt's to overwrite")
				.isEqualTo(500);
	}

	private Execution enqueue(ExecutionType type, String sourcePath, int claimCount) {
		return enqueue(type, sourcePath, claimCount, null);
	}

	private Execution enqueue(ExecutionType type, String sourcePath, int claimCount, String dedupKey) {
		return executionRepository.saveAndFlush(Execution.builder().executionType(type).status(ExecutionStatus.PENDING)
				.sourcePath(sourcePath).claimCount(claimCount).dedupKey(dedupKey).build());
	}
}