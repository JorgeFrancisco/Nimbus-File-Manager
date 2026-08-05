package br.com.jorgemelo.nimbusfilemanager.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintBacklogStartup;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.ConversionWorkspaceCleaner;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.StartupExecutionRecoveryListener;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.InventoryWatchService;
import br.com.jorgemelo.nimbusfilemanager.security.application.DefaultUserInitializer;
import br.com.jorgemelo.nimbusfilemanager.shared.application.StartupRole;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerSupervisor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.worker.application.ExecutionDispatcher;
import br.com.jorgemelo.nimbusfilemanager.worker.application.ParentProcessWatch;
import br.com.jorgemelo.nimbusfilemanager.worker.application.LeaseRenewer;
import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerLoop;

/**
 * Both roles in one JVM, which is what a developer runs from the IDE.
 *
 * <p>
 * The point of these assertions is that combining changes the process boundary
 * and nothing else. There is one of everything - one dispatcher, one renewer,
 * one watcher - and the work still travels the whole way round: the application
 * writes a PENDING row, and the worker half finds it through the queue, claims
 * it, locks it and runs it. Nothing here calls the dispatcher directly, because
 * a test that did would prove only that a method works.
 */
@SpringBootTest
@ActiveProfiles(NimbusProfiles.APP_WORKER_COMBINED)
@Testcontainers
class CombinedProfileIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

	@Autowired
	private ApplicationContext context;

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private OperationLockService operationLockService;

	@Test
	void holdsBothRolesWithOneOfEverything() {
		assertThat(context.getBeansOfType(WorkerLoop.class)).hasSize(1);
		assertThat(context.getBeansOfType(ExecutionDispatcher.class)).hasSize(1);
		assertThat(context.getBeansOfType(LeaseRenewer.class)).hasSize(1);
		assertThat(context.getBeansOfType(InventoryWatchService.class)).hasSize(1);
	}

	@Test
	void activatesBothRolesAndNotAThirdOne() {
		assertThat(context.getEnvironment().getActiveProfiles()).contains(NimbusProfiles.APP, NimbusProfiles.WORKER);
	}

	/**
	 * Everything confined to the application role has to still be here, or
	 * combining would quietly be a third behaviour rather than the two roles
	 * together - and the developer who runs it would stop seeing what production
	 * does.
	 */
	@Test
	void keepsEverythingThatBelongsToTheApplicationRole() {
		assertThat(context.getBeansOfType(StartupExecutionRecoveryListener.class)).hasSize(1);
		assertThat(context.getBeansOfType(FingerprintBacklogStartup.class)).hasSize(1);
		assertThat(context.getBeansOfType(ConversionWorkspaceCleaner.class)).hasSize(1);
		assertThat(context.getBeansOfType(DefaultUserInitializer.class)).hasSize(1);
	}

	/**
	 * The supervisor is what starts a second JVM, and it is absent here by profile
	 * rather than by the property the suite sets: {@code app & !worker} is false
	 * once both are on, so combining cannot launch a worker even in production.
	 */
	@Test
	void startsNoSecondProcess() {
		assertThat(context.getBeansOfType(WorkerSupervisor.class)).isEmpty();
	}

	/**
	 * And therefore has no parent to outlive. A watcher here would be this process
	 * waiting for itself to exit, which is either nothing or a process that ends
	 * itself the moment it is ending anyway.
	 */
	@Test
	void watchesNoParentProcess() {
		assertThat(context.getBeansOfType(ParentProcessWatch.class)).isEmpty();
	}

	/**
	 * The one decision taken before there is a context to inspect. The profile
	 * name contains the word "worker", so anything matching by substring would
	 * take this process for a standalone worker and strip it of what the
	 * application role does outside Spring.
	 */
	@Test
	void isNotTakenForAStandaloneWorkerBeforeSpringStarts() {
		assertThat(StartupRole.isStandaloneWorker(
				new String[] { "--spring.profiles.active=" + NimbusProfiles.APP_WORKER_COMBINED })).isFalse();
	}

	/**
	 * The whole round trip, driven only by writing the row an application would
	 * write. If the queue, the claim, the lock or the handler were bypassed in
	 * this profile, this is the test that would still pass while production
	 * behaved differently - so it asserts the outcome the worker writes, not that
	 * anything was called.
	 */
	@Test
	void runsWorkTheApplicationSideEnqueued(@TempDir Path folder) {
		Execution pending = executionRepository.saveAndFlush(Execution.builder()
				.executionType(ExecutionType.INVENTORY).status(ExecutionStatus.PENDING)
				.sourcePath(folder.toString()).recursive(true).executeFlag(true).build());

		Execution finished = awaitTerminal(pending.getId());

		assertThat(finished.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
		assertThat(finished.getClaimedBy()).isNotNull();
		assertThat(finished.getClaimCount()).isEqualTo(1);
	}

	/**
	 * The standstill, end to end and through the real machinery: a window is
	 * opened, a request is queued, and nothing takes it - not because a flag was
	 * checked somewhere, but because the worker half asked the database and was
	 * told to wait. Then the window closes and the same row runs.
	 *
	 * <p>
	 * This is what an administrative operation depends on, and it is per tree
	 * rather than global: a library switch holds the folders it is replacing, so
	 * nothing starts against them while it runs - and work on any other folder
	 * carries on, which a standstill of the whole queue could not allow.
	 */
	@Test
	void takesNothingWhileAnotherOperationHoldsTheTree(@TempDir Path folder) {
		Execution pending;

		try (var _ = operationLockService.acquire(ExecutionType.LIBRARY_SWITCH, folder)) {
			pending = executionRepository.saveAndFlush(Execution.builder().executionType(ExecutionType.INVENTORY)
					.status(ExecutionStatus.PENDING).sourcePath(folder.toString()).recursive(true).executeFlag(true)
					.build());

			// Long enough for several poll rounds of every loop: the point is that they
			// asked and were refused, not that they had no time to ask.
			//
			// What is asserted is that the work never happens, not that the row never
			// moves. A worker does claim it - the claim comes before the locks - and
			// then finds the tree held and hands it straight back, with its attempts
			// untouched. So the row goes round the loop and never reaches an end.
			await().during(Duration.ofSeconds(12)).atMost(Duration.ofSeconds(30))
					.until(() -> executionRepository.findById(pending.getId()).orElseThrow().getFinishedAt() == null);
		}

		assertThat(awaitTerminal(pending.getId()).getStatus()).isEqualTo(ExecutionStatus.FINISHED);
	}

	/**
	 * Waits for the worker half to reach a verdict. The loops poll, so how long
	 * this takes is a matter of when the next round happens rather than of how
	 * much work there is - the deadline is generous for that reason and short
	 * enough that a broken wiring fails the build instead of hanging it.
	 */
	private Execution awaitTerminal(Long executionId) {
		return await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
				.until(() -> executionRepository.findById(executionId).orElseThrow(),
						execution -> execution.getStatus().isTerminal());
	}
}