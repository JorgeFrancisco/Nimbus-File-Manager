package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

import br.com.jorgemelo.nimbusfilemanager.shared.TestPostgres;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionPossession;
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueue;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.worker.application.LeaseRenewer;
import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerIdentity;
import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerProperties;

/**
 * The one state in which two processes write the same file, and the line
 * between it and the state in which one process merely reports on its own row.
 *
 * <p>
 * Advisory locks belong to a session, and PostgreSQL drops every one of them
 * the moment that session goes - a restarted server, a reset connection, a
 * network that blinked. Nothing is told. The lease, meanwhile, is a row updated
 * through an entirely different connection, and it went on being renewed: the
 * queue said "claimed, healthy, mine" about an execution that held nothing, and
 * the job carried on moving files with no exclusion at all.
 *
 * <p>
 * Losing the locks stops the <em>work</em>. It does not stop the taking, and
 * the two are asked for separately here because they were once one answer: an
 * execution whose lock session died could no longer write the very sentence
 * that explained why it had stopped, and the row sat RUNNING until recovery
 * came for it.
 *
 * <p>
 * A real database is the only place this can be shown. The loss is something
 * the server does, not something the code decides, and a mocked connection
 * would only prove that a method was called.
 */
@SpringBootTest
@Testcontainers
class OwnershipLossIntegrationTest {

	private static final int LEASE_SECONDS = 600;

	private static final int MAX_CLAIMS = 3;

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
	private OperationLockService operationLockService;

	@Autowired
	private ExecutionQueue executionQueue;

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private ExecutionProgressService executionProgressService;

	private final WorkerIdentity identity = new WorkerIdentity();

	@Test
	void aLostLockSessionEndsTheOwnershipTheLeaseWasStillClaiming(@TempDir Path folder) throws SQLException {
		ClaimedExecution claimed = claim(folder);

		OperationLock lock = operationLockService.acquire(ExecutionType.INVENTORY, folder);

		ExecutionOwnership ownership = new ExecutionOwnership(claimed.id(), lock, operationLockService,
				new ExecutionOwnershipGuard(mock(ExecutionQueue.class)));

		LeaseRenewer renewer = renewer();

		renewer.hold(ownership);

		assertThat(ownership.mayGoOnWorking()).isTrue();

		renewer.renew();

		LocalDateTime renewedWhileOwned = leaseOf(claimed.id());

		assertThat(renewedWhileOwned).isNotNull();

		// What the server does when a connection goes: every advisory lock it held
		// is released, and nobody is told.
		lock.session().close();

		assertThat(ownership.stillHoldsOperationLock()).isFalse();
		assertThat(ownership.mayGoOnWorking()).isFalse();

		assertThatExceptionOfType(OwnershipLostException.class).isThrownBy(ownership::assertMayGoOnWorking);

		// And the lease stops being extended, so the execution becomes recoverable
		// instead of looking owned forever by a worker that owns nothing.
		renewer.renew();

		assertThat(leaseOf(claimed.id())).isEqualTo(renewedWhileOwned);

		// What the dispatcher does in its finally, and what has to work even here:
		// giving back locks that the server already took away.
		ownership.close();
	}

	/**
	 * The gap the two properties were split to close.
	 *
	 * <p>
	 * The session holding the path locks is gone, so nothing may touch the user's
	 * files any more - but the row is still this taking's, and saying "I stopped"
	 * is the one thing it still has every right and every reason to do. It used to
	 * be refused by the same answer that stopped the work, and the execution sat
	 * RUNNING on every screen until the lease lapsed and recovery came for it.
	 */
	@Test
	void aTakingThatLostItsLocksStillRecordsThatItStopped(@TempDir Path folder, @TempDir Path other)
			throws SQLException {
		ExecutionOwnershipGuard guard = new ExecutionOwnershipGuard(mock(ExecutionQueue.class));

		OperationLock lock = operationLockService.acquire(ExecutionType.INVENTORY, folder);

		ExecutionOwnership ownership = takingOf(claim(folder), lock, guard);

		// A taking of its own beside it, over another row, which loses nothing.
		ExecutionOwnership unaffected = takingOf(claim(other), null, guard);

		lock.session().close();

		assertThat(ownership.stillHoldsOperationLock()).as("the work may not go on").isFalse();
		assertThat(ownership.takingIsStillCurrent()).as("but the row is still this taking's").isTrue();

		executionProgressService.interrupt(ownership, ExecutionMessages.executionInterrupted());

		Execution stopped = reload(ownership.executionId());

		assertThat(stopped.getStatus()).isEqualTo(ExecutionStatus.INTERRUPTED);
		assertThat(stopped.getFinishedAt()).isNotNull();

		// Nothing of it reached the taking next to it.
		assertThat(unaffected.takingIsStillCurrent()).isTrue();
		assertThat(unaffected.mayGoOnWorking()).isTrue();
		assertThat(reload(unaffected.executionId()).getStatus()).isEqualTo(ExecutionStatus.RUNNING);

		ownership.close();
		unaffected.close();
	}

	/**
	 * And losing the locks buys nothing on the other side of the line: a taking
	 * that a later one has replaced is refused whether or not it still holds a
	 * tree, because that refusal was never about the tree.
	 */
	@Test
	void losingTheLocksDoesNotLetAReplacedTakingWriteEither(@TempDir Path folder) throws SQLException {
		ExecutionOwnershipGuard guard = new ExecutionOwnershipGuard(mock(ExecutionQueue.class));

		ClaimedExecution claimed = claim(folder);

		OperationLock lock = operationLockService.acquire(ExecutionType.INVENTORY, folder);

		ExecutionOwnership ownership = takingOf(claimed, lock, guard);

		lock.session().close();

		// Recovery gave the row back and it was taken again, by the same name.
		guard.takes(new ExecutionPossession(claimed.id(), identity.workerId(), ownership.claimCount() + 1));

		assertThat(ownership.takingIsStillCurrent()).isFalse();

		executionProgressService.finish(ownership, ExecutionStatus.FINISHED, 9, 9, 0, 0,
				ExecutionMessages.inventoryCompleted());

		assertThat(reload(claimed.id()).getStatus()).isEqualTo(ExecutionStatus.RUNNING);

		ownership.close();
	}

	/**
	 * The ordinary end of an execution, for contrast: giving the locks back is
	 * also a loss of ownership, and the renewer has to stop just the same - a
	 * lease extended past the work is a row nobody will ever take back.
	 */
	@Test
	void givingTheLocksBackAlsoStopsTheLease(@TempDir Path folder) {
		ClaimedExecution claimed = claim(folder);

		ExecutionOwnership ownership = operationLockService.acquireFor(claimed.id(), ExecutionType.INVENTORY, folder);

		LeaseRenewer renewer = renewer();

		renewer.hold(ownership);
		renewer.renew();

		LocalDateTime renewedWhileOwned = leaseOf(claimed.id());

		ownership.close();

		assertThat(ownership.takingIsStillCurrent()).isFalse();
		assertThat(ownership.mayGoOnWorking()).isFalse();

		renewer.renew();

		assertThat(leaseOf(claimed.id())).isEqualTo(renewedWhileOwned);
	}

	/**
	 * A taking with its attempt counted and registered, the way the dispatcher
	 * builds one: the number is what a later taking of the same row would differ
	 * from, so a test that left it unset would be proving less than it looks.
	 *
	 * @param lock the tree it holds, or {@code null} for work whose type holds
	 * none
	 */
	private ExecutionOwnership takingOf(ClaimedExecution claimed, OperationLock lock,
			ExecutionOwnershipGuard guard) {
		ExecutionOwnership ownership = new ExecutionOwnership(claimed.id(), lock, operationLockService, guard);

		int attempt = executionQueue.countAttempt(claimed.id(), identity.workerId(), MAX_CLAIMS).orElseThrow();

		ownership.attemptStarted(attempt);

		guard.takes(new ExecutionPossession(claimed.id(), identity.workerId(), attempt));

		return ownership;
	}

	/**
	 * Reserves through the queue rather than writing a RUNNING row by hand, so the
	 * lease under test is the one production creates.
	 */
	private ClaimedExecution claim(Path folder) {
		executionRepository.saveAndFlush(Execution.builder().executionType(ExecutionType.INVENTORY)
				.status(ExecutionStatus.PENDING).sourcePath(folder.toString()).recursive(true).executeFlag(true)
				.createdAt(LocalDateTime.now(clock)).availableAt(LocalDateTime.now(clock)).build());

		return executionQueue.reserve(identity.workerId(), List.of(ExecutionType.INVENTORY.name()), MAX_CLAIMS,
				LEASE_SECONDS).orElseThrow();
	}

	private LeaseRenewer renewer() {
		return new LeaseRenewer(executionQueue, new ExecutionOwnershipGuard(mock(ExecutionQueue.class)),
				new WorkerProperties(null, LEASE_SECONDS, null, null, null, null, null, null, null, null), identity);
	}

	private Execution reload(long executionId) {
		return executionRepository.findById(executionId).orElseThrow();
	}

	private LocalDateTime leaseOf(long executionId) {
		return executionRepository.findById(executionId).orElseThrow().getLeaseUntil();
	}
}