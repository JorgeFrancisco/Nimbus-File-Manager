package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import br.com.jorgemelo.nimbusfilemanager.shared.TestPostgres;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * Two callers asking for the same work at the same moment, each from a
 * transaction of its own.
 *
 * <p>
 * Asking before inserting removes the ordinary duplicate - the one committed
 * long before anybody asked again. What it cannot remove is the case where both
 * callers look, both find nothing, and both insert: the second is refused by the
 * deduplication index, and that refusal marks <em>its caller's</em> transaction
 * rollback-only. Measured before it was fixed, and it cost an inventory batch
 * every write it had made.
 *
 * <p>
 * So admission takes an advisory lock on the identity first, and the second
 * caller waits rather than races. It holds that lock until its own transaction
 * ends, which is why a caller with several requests hands them all over at once:
 * the whole set is sorted and taken in one order, and two callers wanting the
 * same pair in opposite orders therefore cannot form a cycle.
 *
 * <p>
 * <b>Determinism comes from the database, not from timing.</b> One caller holds
 * its transaction open while another starts, and the test waits for the wait
 * itself to be visible in {@code pg_locks} before letting the first commit - so
 * the interleaving is observed rather than hoped for. Where the point is that
 * two admissions do <em>not</em> block, the proof is the opposite: the second
 * finishes while the first is still holding, and a wait would time out.
 */
@SpringBootTest
@Testcontainers
class ConcurrentAdmissionIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = TestPostgres.container();

	@Autowired
	private ExecutionEnqueueService executionEnqueueService;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private ExecutorService callers;

	@BeforeEach
	void startCallers() {
		callers = Executors.newFixedThreadPool(2);
	}

	@AfterEach
	void stopCallers() {
		callers.shutdownNow();
	}

	/** The race that was measured: both look, both find nothing, both insert. */
	@Test
	void twoCallersPassingTheCheckTogetherNeitherPoisonsItsOwnTransaction() throws Exception {
		CountDownLatch admitted = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		Future<Throwable> winner = holding(List.of(verification("contested")), admitted, release);

		Assertions.assertThat(admitted.await(30, TimeUnit.SECONDS)).as("the first caller admitted").isTrue();

		Future<Throwable> loser = admitting(List.of(verification("contested")));

		awaitWaitingOnAdmission();

		release.countDown();

		Assertions.assertThat(winner.get(30, TimeUnit.SECONDS)).as("the winner commits").isNull();
		Assertions.assertThat(loser.get(30, TimeUnit.SECONDS))
				.as("and losing a race it never knew it was in costs the other caller nothing").isNull();

		Assertions.assertThat(queued(ExecutionType.CONTENT_VERIFICATION, "contested"))
				.as("one intention, represented once").hasSize(1);
	}

	/**
	 * The deadlock the locks would otherwise introduce, and the reason a caller
	 * hands its whole set over at once: these two ask for the same pair in
	 * opposite orders, and the authority sorts both the same way.
	 */
	@Test
	void twoCallersWantingTheSamePairInOppositeOrdersDoNotDeadlock() throws Exception {
		CountDownLatch admitted = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		Future<Throwable> ascending = holding(List.of(verification("pair-a"), verification("pair-b")), admitted,
				release);

		Assertions.assertThat(admitted.await(30, TimeUnit.SECONDS)).as("the first caller admitted both").isTrue();

		Future<Throwable> descending = admitting(List.of(verification("pair-b"), verification("pair-a")));

		awaitWaitingOnAdmission();

		release.countDown();

		Assertions.assertThat(ascending.get(30, TimeUnit.SECONDS)).as("the pass that arrived first commits").isNull();
		Assertions.assertThat(descending.get(30, TimeUnit.SECONDS))
				.as("and the one that listed its keys the other way round neither deadlocks nor is refused").isNull();

		Assertions.assertThat(queued(ExecutionType.CONTENT_VERIFICATION, "pair-a")).hasSize(1);
		Assertions.assertThat(queued(ExecutionType.CONTENT_VERIFICATION, "pair-b")).hasSize(1);
	}

	/** Unrelated work never waits: the lock is on an identity, not the queue. */
	@Test
	void admissionsOfDifferentKeysDoNotWaitForEachOther() throws Exception {
		CountDownLatch admitted = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		Future<Throwable> holder = holding(List.of(verification("independent-a")), admitted, release);

		Assertions.assertThat(admitted.await(30, TimeUnit.SECONDS)).isTrue();

		Future<Throwable> unrelated = admitting(List.of(verification("independent-b")));

		Assertions.assertThat(unrelated.get(20, TimeUnit.SECONDS))
				.as("finished while the other transaction was still holding its own key").isNull();

		release.countDown();

		Assertions.assertThat(holder.get(30, TimeUnit.SECONDS)).isNull();

		Assertions.assertThat(queued(ExecutionType.CONTENT_VERIFICATION, "independent-a")).hasSize(1);
		Assertions.assertThat(queued(ExecutionType.CONTENT_VERIFICATION, "independent-b")).hasSize(1);
	}

	/**
	 * The type is part of the identity. An inventory of a folder and a reconcile of
	 * the same folder carry the same deduplication key and are not the same
	 * intention - they must not take turns over a string they happen to share.
	 */
	@Test
	void theSameKeyUnderADifferentTypeIsADifferentIntention() throws Exception {
		CountDownLatch admitted = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		Future<Throwable> holder = holding(List.of(verification("shared-spelling")), admitted, release);

		Assertions.assertThat(admitted.await(30, TimeUnit.SECONDS)).isTrue();

		Future<Throwable> otherType = admitting(
				List.of(request(ExecutionType.RECONCILE, "content-verification:shared-spelling")));

		Assertions.assertThat(otherType.get(20, TimeUnit.SECONDS)).as("a different type never waits").isNull();

		release.countDown();

		Assertions.assertThat(holder.get(30, TimeUnit.SECONDS)).isNull();

		Assertions.assertThat(queued(ExecutionType.CONTENT_VERIFICATION, "shared-spelling")).hasSize(1);
		Assertions.assertThat(queued(ExecutionType.RECONCILE, "shared-spelling")).hasSize(1);
	}

	/**
	 * The 1 + 1 rule, which the advisory lock must not quietly turn into 1 + 0.
	 *
	 * <p>
	 * A running execution has looked at what it looked at. A request arriving now
	 * is about something observed after that, and the caller has no way to hand it
	 * to work already in progress - so it becomes the successor, and refusing it
	 * would drop an observation nothing makes again on its own. What a successor
	 * may not do is pile up: the one already waiting is the answer to every further
	 * request until it runs.
	 */
	@Test
	void aRunningExecutionDoesNotAnswerARequestAboutWhatHappenedAfterIt() {
		Execution running = transaction()
				.execute(_ -> executionEnqueueService.enqueue(verification("successor")).orElseThrow());

		claim(running.getId());

		Optional<Execution> successor = transaction()
				.execute(_ -> executionEnqueueService.enqueue(verification("successor")));

		Optional<Execution> third = transaction()
				.execute(_ -> executionEnqueueService.enqueue(verification("successor")));

		Assertions.assertThat(successor).as("nothing is waiting, so the new observation becomes the successor")
				.isPresent();
		Assertions.assertThat(third).as("and the successor already waiting answers everything after it").isEmpty();

		Assertions.assertThat(queued(ExecutionType.CONTENT_VERIFICATION, "successor"))
				.as("one running and one waiting - never more").hasSize(2);
	}

	/** Takes the row the way a worker does, so it is running rather than waiting. */
	private void claim(Long executionId) {
		jdbcTemplate.update("UPDATE execution SET status = 'RUNNING', started_at = now() WHERE id = ?", executionId);
	}

	/**
	 * Atomicity, unchanged by any of this: the request belongs to the transaction
	 * that justified it. A batch that fails after asking leaves nothing behind
	 * asking about a state that was never recorded.
	 */
	@Test
	void rollingBackTheCallerTakesTheAdmissionWithIt() {
		Assertions.assertThatIllegalStateException().isThrownBy(() -> transaction().executeWithoutResult(_ -> {
			executionEnqueueService.enqueueAll(List.of(verification("undone")));

			throw new IllegalStateException("the batch failed after asking");
		}));

		Assertions.assertThat(queued(ExecutionType.CONTENT_VERIFICATION, "undone")).isEmpty();
	}

	/** Admits in its own transaction, then holds that transaction open. */
	private Future<Throwable> holding(List<Execution> intents, CountDownLatch admitted, CountDownLatch release) {
		return callers.submit(outcomeOf(() -> transaction().executeWithoutResult(_ -> {
			executionEnqueueService.enqueueAll(intents);

			admitted.countDown();

			awaitQuietly(release);
		})));
	}

	private Future<Throwable> admitting(List<Execution> intents) {
		return callers.submit(outcomeOf(() -> transaction()
				.executeWithoutResult(_ -> executionEnqueueService.enqueueAll(intents))));
	}

	/**
	 * Waits until PostgreSQL reports an advisory lock asked for and not granted,
	 * which is the second caller taking its turn. Polled rather than slept on: a
	 * sleep makes the interleaving a guess, and a guess that is usually right is
	 * how a race stops being tested at all.
	 */
	private void awaitWaitingOnAdmission() {
		await().alias("the second caller never waited, so no race was observed").atMost(Duration.ofSeconds(30))
				.pollInterval(Duration.ofMillis(50)).until(this::someoneIsWaitingForAnAdmissionLock);
	}

	private boolean someoneIsWaitingForAnAdmissionLock() {
		Integer waiting = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted", Integer.class);

		return waiting != null && waiting > 0;
	}

	private Callable<Throwable> outcomeOf(Runnable work) {
		return () -> {
			try {
				work.run();

				return null;
			} catch (Throwable thrown) {
				return thrown;
			}
		};
	}

	private void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(30, TimeUnit.SECONDS);
		} catch (InterruptedException _) {
			Thread.currentThread().interrupt();
		}
	}

	private TransactionTemplate transaction() {
		return new TransactionTemplate(transactionManager);
	}

	private Execution verification(String file) {
		return request(ExecutionType.CONTENT_VERIFICATION, "content-verification:" + file);
	}

	private Execution request(ExecutionType executionType, String dedupKey) {
		return Execution.builder().executionType(executionType).triggerEvent(ExecutionTrigger.TIMER)
				.sourcePath("d:\\library\\" + dedupKey).executeFlag(true).dedupKey(dedupKey).build();
	}

	private List<Long> queued(ExecutionType executionType, String file) {
		return jdbcTemplate.queryForList("SELECT id FROM execution WHERE execution_type = ? AND dedup_key = ?",
				Long.class, executionType.name(), "content-verification:" + file);
	}
}