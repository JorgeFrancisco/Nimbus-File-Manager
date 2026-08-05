package br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnershipGuard;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * The other half of the fence, seen from the side that loses.
 *
 * <p>
 * Every other proof in this slice comes at the pin from the front: an obsolete
 * taking asks, and is refused. That leaves the harder question untouched -
 * whether a taking that was <em>granted</em> the pin can be overtaken while it
 * is using it. If recovery could hand the row to somebody else between the pin
 * and the mutation it authorised, the pin would answer about a past that had
 * already stopped being true, and every boundary built on it would be
 * decoration.
 *
 * <p>
 * What makes it hold is that the pin is a {@code FOR SHARE} on the row and every
 * ownership change is a write to that same row, so PostgreSQL will not let the
 * second happen while the first is open. That is what is shown here, and it is
 * shown by asking the database rather than by watching a clock:
 * {@code pg_blocking_pids} names the session recovery is waiting on, and it is
 * the one holding the pin. The deadline in the loop is there so a broken build
 * fails instead of hanging - the proof is the blocking, never the elapsed time.
 *
 * <p>
 * Time moves the way it really does, without anything waiting for it: the
 * recovery pass runs off a clock an hour ahead, which is the same row seen after
 * a lease has quietly run out.
 */
@SpringBootTest
@Testcontainers
class ExecutionPinExclusionIntegrationTest {

	private static final String WORKER = "worker-holding-the-pin";

	private static final int HANG_GUARD_SECONDS = 30;

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private ExecutionOwnershipGuard executionOwnershipGuard;

	@Autowired
	private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private final ExecutorService threads = Executors.newFixedThreadPool(2);

	@AfterEach
	void forgetEverything() {
		threads.shutdownNow();

		executionRepository.deleteAll();
	}

	/**
	 * Recovery arrives in the middle of a fenced mutation and waits its turn.
	 *
	 * <p>
	 * The taking pins and stays inside its transaction, which is the whole of the
	 * window a domain mutation lives in. Recovery finds the lease expired and tries
	 * to put the row back on the queue - the write every change of ownership makes
	 * - and PostgreSQL parks it, naming the pin as the reason. Only once the
	 * mutation has committed does the row change hands, in that order and never the
	 * other.
	 */
	@Test
	void aRecoveryThatArrivesDuringAPinWaitsForItToCommit() throws Exception {
		long executionId = claimedAt(1);

		ExecutionOwnership pinning = Takings.fenced(executionId, WORKER, 1, executionOwnershipGuard);

		CountDownLatch pinned = new CountDownLatch(1);
		CountDownLatch mutationCommitted = new CountDownLatch(1);
		CountDownLatch recoveryConnected = new CountDownLatch(1);

		AtomicInteger pinningSession = new AtomicInteger();
		AtomicInteger recoverySession = new AtomicInteger();

		Future<Boolean> holdsThePin = threads.submit(() -> shortTransaction().execute(_ -> {
			pinningSession.set(backendPid());

			boolean granted = pinning.pin();

			pinned.countDown();

			awaitWithinTheHangGuard(mutationCommitted);

			return granted;
		}));

		assertThat(pinned.await(HANG_GUARD_SECONDS, SECONDS)).as("the pin was asked for").isTrue();

		Future<Boolean> recovery = threads.submit(() -> shortTransaction().execute(_ -> {
			recoverySession.set(backendPid());

			recoveryConnected.countDown();

			return anHourLater().requeue(executionId);
		}));

		assertThat(recoveryConnected.await(HANG_GUARD_SECONDS, SECONDS)).as("recovery has its own session").isTrue();

		waitUntilBlocked(recoverySession, pinningSession);

		assertThat(recovery.isDone()).as("recovery has not put the row back").isFalse();
		assertThat(claimCountOf(executionId)).as("and the row is still the pinned taking's").isEqualTo(1);

		mutationCommitted.countDown();

		assertThat(holdsThePin.get(HANG_GUARD_SECONDS, SECONDS)).as("the pin was granted all along").isTrue();
		assertThat(recovery.get(HANG_GUARD_SECONDS, SECONDS)).as("and recovery proceeds once it commits").isTrue();

		Execution row = executionRepository.findById(executionId).orElseThrow();

		assertThat(row.getStatus()).isEqualTo(ExecutionStatus.PENDING);
		assertThat(row.getClaimedBy()).isNull();
	}

	/**
	 * Blocks until PostgreSQL says the recovery session is waiting on the session
	 * that holds the pin. Asking who blocks whom is the observation; the deadline
	 * only decides how a build that broke this reports it.
	 */
	private void waitUntilBlocked(AtomicInteger waiting, AtomicInteger blocker) {
		long deadline = System.nanoTime() + SECONDS.toNanos(HANG_GUARD_SECONDS);

		while (!Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class,
				blocker.get(), waiting.get()))) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("recovery was never blocked by the pin");
			}

			Thread.onSpinWait();
		}
	}

	/**
	 * The queue as recovery sees it after the lease has run out. Advancing the
	 * clock rather than waiting for one keeps the moment exact - the row is
	 * untouched, and only the question being asked of it has moved on.
	 */
	private ExecutionQueue anHourLater() {
		return new ExecutionQueue(namedParameterJdbcTemplate,
				Clock.fixed(LocalDateTime.now().plusHours(1).atZone(ZoneId.systemDefault()).toInstant(),
						ZoneId.systemDefault()));
	}

	private TransactionTemplate shortTransaction() {
		TransactionTemplate template = new TransactionTemplate(transactionManager);

		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

		return template;
	}

	private int backendPid() {
		Integer pid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);

		return pid == null ? 0 : pid;
	}

	private void awaitWithinTheHangGuard(CountDownLatch latch) {
		try {
			if (!latch.await(HANG_GUARD_SECONDS, SECONDS)) {
				throw new IllegalStateException("nobody ever released the pin");
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();

			throw new IllegalStateException(interrupted);
		}
	}

	private int claimCountOf(long executionId) {
		Integer claimCount = jdbcTemplate.queryForObject("SELECT claim_count FROM execution WHERE id = ?",
				Integer.class, executionId);

		return claimCount == null ? 0 : claimCount;
	}

	private long claimedAt(int claimCount) {
		return executionRepository.saveAndFlush(Execution.builder().executionType(ExecutionType.CATALOG_PURGE)
				.status(ExecutionStatus.RUNNING).recursive(false).executeFlag(true).claimedBy(WORKER)
				.claimCount(claimCount).claimedAt(LocalDateTime.now())
				.leaseUntil(LocalDateTime.now().plusMinutes(10)).build()).getId();
	}
}