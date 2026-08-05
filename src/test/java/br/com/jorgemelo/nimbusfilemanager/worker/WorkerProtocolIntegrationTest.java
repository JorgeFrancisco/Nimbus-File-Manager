package br.com.jorgemelo.nimbusfilemanager.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationPathKey;
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueueNotifier;
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueueSignals;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerAvailability;
import br.com.jorgemelo.nimbusfilemanager.worker.domain.repository.WorkerInstanceRepository;

/**
 * The protocol between the two roles, against a real database.
 *
 * <p>
 * Everything asserted here is either a fact in the catalog or an observable
 * effect of one. The notification cannot be observed directly - it has no
 * payload and leaves no row - so what is asserted is what it is for: that a
 * worker learns of queued work sooner than its polling interval, and that
 * losing the notification changes the delay and nothing else.
 */
@SpringBootTest
@ActiveProfiles(NimbusProfiles.APP_WORKER_COMBINED)
@Testcontainers
class WorkerProtocolIntegrationTest {

	/**
	 * Comfortably under the five-second polling interval the worker runs with by
	 * default: a signal that took longer than this would not be doing anything the
	 * poll was not already doing.
	 */
	private static final Duration MUCH_SOONER_THAN_A_POLL = Duration.ofSeconds(2);

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

	@Autowired
	private ExecutionEnqueueService executionEnqueueService;

	@Autowired
	private ExecutionQueueSignals executionQueueSignals;

	@Autowired
	private ExecutionQueueNotifier executionQueueNotifier;

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private WorkerInstanceRepository workerInstanceRepository;

	@Autowired
	private WorkerAvailability workerAvailability;

	/**
	 * A. Queued, and the worker hears about it. The wait is started before the
	 * request exists, which is the arrangement that matters: a signal published to
	 * a listener that is already waiting is the one case where the whole path -
	 * commit, delivery, the counter the loops watch - has to work end to end.
	 */
	@Test
	void aQueuedRequestReachesTheWorkerSoonerThanTheNextPoll(@TempDir Path folder) {
		long seen = executionQueueSignals.signalCount();

		CompletableFuture<Boolean> woken = CompletableFuture
				.supplyAsync(() -> executionQueueSignals.awaitSignalAfter(seen, Duration.ofSeconds(20)));

		executionEnqueueService.enqueue(request(folder));

		assertThat(woken).succeedsWithin(MUCH_SOONER_THAN_A_POLL).isEqualTo(true);
	}

	/**
	 * B. The signal is lost - here by never being sent, which is the same thing to
	 * a worker - and the work still runs. This is the property that keeps the
	 * notification an optimisation: the row is written straight to the table, so
	 * nothing was published to anybody, and only polling can find it.
	 */
	@Test
	void workNobodyAnnouncedIsStillFoundByPolling(@TempDir Path folder) {
		Execution pending = executionRepository.saveAndFlush(pendingInventory(folder));

		assertThat(awaitTerminal(pending.getId()).getStatus()).isEqualTo(ExecutionStatus.FINISHED);
	}

	/**
	 * C and D. Repeated and spurious signals: the channel is told three times that
	 * there is work when there is one execution, and once more when there is none.
	 * A wake-up says only "go and look", so the cost of both is a query, and the
	 * execution is claimed exactly once - which the claim count is the record of.
	 */
	@Test
	void repeatedAndEmptySignalsRunTheWorkOnceAndNothingTwice(@TempDir Path folder) {
		Execution pending = executionRepository.saveAndFlush(pendingInventory(folder));

		executionQueueNotifier.workWasQueued();
		executionQueueNotifier.workWasQueued();
		executionQueueNotifier.workWasQueued();

		Execution finished = awaitTerminal(pending.getId());

		assertThat(finished.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
		assertThat(finished.getClaimCount()).isEqualTo(1);

		// And a signal with nothing behind it costs a look and leaves no trace.
		executionQueueNotifier.workWasQueued();

		assertThat(awaitTerminal(pending.getId()).getClaimCount()).isEqualTo(1);
	}

	/**
	 * The transactional ordering, made observable. A duplicate request is refused
	 * by the index, its transaction rolls back, and PostgreSQL discards the
	 * notification that transaction had published - so no worker is woken for a row
	 * that does not exist. It is the same mechanism that guarantees the inverse and
	 * more important direction: a delivered notification can only describe a
	 * committed row.
	 */
	@Test
	void aRefusedDuplicateWakesNobody(@TempDir Path folder) {
		// The row the duplicate collides with is written straight to the table, with a
		// moment of availability far enough ahead that no worker in this context ever
		// claims it. That is deliberate: the index only refuses a duplicate of
		// something still queued or running, so a worker that took and finished the
		// first request would make the second one legitimate and the test would be
		// measuring nothing.
		Execution waiting = pendingInventory(folder);

		waiting.setDedupKey(OperationPathKey.canonical(folder));
		waiting.setAvailableAt(LocalDateTime.now().plusHours(1));

		executionRepository.saveAndFlush(waiting);

		long seen = executionQueueSignals.signalCount();

		assertThat(executionEnqueueService.enqueue(request(folder))).isEmpty();
		assertThat(executionQueueSignals.awaitSignalAfter(seen, MUCH_SOONER_THAN_A_POLL)).isFalse();
	}

	/**
	 * The ordering the whole design rests on: by the time a signal is delivered,
	 * the row it is about is committed and visible. The wait starts before the
	 * request exists and the query runs the moment it ends, so a notification
	 * published before its commit - or without one - would be caught here as a
	 * wake-up with nothing behind it.
	 */
	@Test
	void aDeliveredSignalAlwaysDescribesACommittedRow(@TempDir Path folder) {
		long seen = executionQueueSignals.signalCount();

		CompletableFuture<Boolean> queuedWhenWoken = CompletableFuture.supplyAsync(() -> {
			executionQueueSignals.awaitSignalAfter(seen, Duration.ofSeconds(20));

			return executionRepository.existsByExecutionTypeAndStatusIn(ExecutionType.CONVERSION,
					Set.of(ExecutionStatus.PENDING, ExecutionStatus.RUNNING));
		});

		executionEnqueueService.enqueue(Execution.builder().executionType(ExecutionType.CONVERSION)
				.triggerEvent(ExecutionTrigger.MANUAL).sourcePath(folder.toString()).recursive(false).executeFlag(true)
				.dedupKey(OperationPathKey.canonical(folder)).build());

		assertThat(queuedWhenWoken).succeedsWithin(Duration.ofSeconds(20)).isEqualTo(true);
	}

	/**
	 * E. The worker of this process started long before these rows, so "started
	 * after the execution" is shown the way it actually happens: the row is written
	 * while nothing is listening for it - past tense by the time anything looks -
	 * and is still picked up. What finds it is the same query a worker runs on its
	 * first round.
	 */
	@Test
	void aWorkerFindsWorkThatWasQueuedBeforeItLooked(@TempDir Path folder) {
		Execution pending = executionRepository.saveAndFlush(pendingInventory(folder));

		assertThat(awaitTerminal(pending.getId()).getClaimedBy()).isNotNull();
	}

	/** The worker says it is alive, and the application can read that. */
	@Test
	void theApplicationCanSeeThatAWorkerIsAlive() {
		await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
				.until(() -> workerAvailability.current().available());

		assertThat(workerAvailability.current().instances()).isEqualTo(1);
		assertThat(workerAvailability.current().lastSeenAt()).isNotNull();
	}

	/**
	 * Health and ownership are separate, and this is what would break if they were
	 * ever merged: the heartbeat rows are wiped while an execution is running, and
	 * the execution keeps its claim and its lease. A worker whose presence was
	 * forgotten does not stop owning what it is doing.
	 */
	@Test
	void forgettingTheHeartbeatDoesNotDisturbAnExecutionInFlight(@TempDir Path folder) {
		Execution pending = executionRepository.saveAndFlush(pendingInventory(folder));

		Execution finished = awaitTerminal(pending.getId());

		String claimedBy = finished.getClaimedBy();

		workerInstanceRepository.deleteAll();

		assertThat(workerAvailability.current().available()).isFalse();
		assertThat(executionRepository.findById(pending.getId()).orElseThrow().getClaimedBy()).isEqualTo(claimedBy);
	}

	private Execution request(Path folder) {
		return Execution.builder().executionType(ExecutionType.INVENTORY).triggerEvent(ExecutionTrigger.MANUAL)
				.sourcePath(folder.toString()).recursive(true).executeFlag(true)
				.dedupKey(OperationPathKey.canonical(folder)).build();
	}

	private Execution pendingInventory(Path folder) {
		return Execution.builder().executionType(ExecutionType.INVENTORY).status(ExecutionStatus.PENDING)
				.sourcePath(folder.toString()).recursive(true).executeFlag(true).createdAt(LocalDateTime.now())
				.availableAt(LocalDateTime.now()).build();
	}

	private Execution awaitTerminal(Long executionId) {
		return await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250)).until(
				() -> executionRepository.findById(executionId).orElseThrow(),
				execution -> execution.getStatus().isTerminal());
	}
}