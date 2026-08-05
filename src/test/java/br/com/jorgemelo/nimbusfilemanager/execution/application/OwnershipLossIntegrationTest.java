package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueue;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.worker.application.LeaseRenewer;
import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerIdentity;
import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerProperties;

/**
 * The one state in which two processes write the same file.
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
 * A real database is the only place this can be shown. The loss is something
 * the server does, not something the code decides, and a mocked connection
 * would only prove that a method was called.
 */
@SpringBootTest
@Testcontainers
class OwnershipLossIntegrationTest {

	private static final int LEASE_SECONDS = 600;

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

	@Autowired
	private OperationLockService operationLockService;

	@Autowired
	private ExecutionQueue executionQueue;

	@Autowired
	private ExecutionRepository executionRepository;

	private final WorkerIdentity identity = new WorkerIdentity();

	@Test
	void aLostLockSessionEndsTheOwnershipTheLeaseWasStillClaiming(@TempDir Path folder) throws SQLException {
		ClaimedExecution claimed = claim(folder);

		OperationLock lock = operationLockService.acquire(ExecutionType.INVENTORY, folder);

		ExecutionOwnership ownership = new ExecutionOwnership(claimed.id(), lock, operationLockService);

		LeaseRenewer renewer = renewer();

		renewer.hold(ownership);

		assertThat(ownership.isStillOwned()).isTrue();

		renewer.renew();

		LocalDateTime renewedWhileOwned = leaseOf(claimed.id());

		assertThat(renewedWhileOwned).isNotNull();

		// What the server does when a connection goes: every advisory lock it held
		// is released, and nobody is told.
		lock.session().close();

		assertThat(ownership.isStillOwned()).isFalse();

		assertThatExceptionOfType(OwnershipLostException.class).isThrownBy(ownership::assertStillOwned);

		// And the lease stops being extended, so the execution becomes recoverable
		// instead of looking owned forever by a worker that owns nothing.
		renewer.renew();

		assertThat(leaseOf(claimed.id())).isEqualTo(renewedWhileOwned);

		// What the dispatcher does in its finally, and what has to work even here:
		// giving back locks that the server already took away.
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

		assertThat(ownership.isStillOwned()).isFalse();

		renewer.renew();

		assertThat(leaseOf(claimed.id())).isEqualTo(renewedWhileOwned);
	}

	/**
	 * Reserves through the queue rather than writing a RUNNING row by hand, so the
	 * lease under test is the one production creates.
	 */
	private ClaimedExecution claim(Path folder) {
		executionRepository.saveAndFlush(Execution.builder().executionType(ExecutionType.INVENTORY)
				.status(ExecutionStatus.PENDING).sourcePath(folder.toString()).recursive(true).executeFlag(true)
				.createdAt(LocalDateTime.now()).availableAt(LocalDateTime.now()).build());

		return executionQueue.reserve(identity.workerId(), List.of(ExecutionType.INVENTORY.name()), 3, LEASE_SECONDS)
				.orElseThrow();
	}

	private LeaseRenewer renewer() {
		return new LeaseRenewer(executionQueue,
				new WorkerProperties(null, LEASE_SECONDS, null, null, null, null, null, null, null, null), identity);
	}

	private LocalDateTime leaseOf(long executionId) {
		return executionRepository.findById(executionId).orElseThrow().getLeaseUntil();
	}
}