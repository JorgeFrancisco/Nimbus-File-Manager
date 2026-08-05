package br.com.jorgemelo.nimbusfilemanager.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintBacklogStartup;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.ConversionWorkspaceCleaner;
import br.com.jorgemelo.nimbusfilemanager.database.application.EmbeddedClusterService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.execution.application.StartupExecutionRecoveryListener;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.InventoryWatchService;
import br.com.jorgemelo.nimbusfilemanager.security.application.DefaultUserInitializer;
import br.com.jorgemelo.nimbusfilemanager.shared.application.StartupRole;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.worker.application.ExecutionDispatcher;
import br.com.jorgemelo.nimbusfilemanager.worker.application.ParentProcessWatch;
import br.com.jorgemelo.nimbusfilemanager.worker.application.LeaseRenewer;
import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerLoop;

/**
 * What a worker is, expressed as what its context contains.
 *
 * <p>
 * The role is a profile, so it is only real if the beans follow it. This asserts
 * both halves: the machinery for claiming work is there, and the things a worker
 * has no business doing - serving screens, watching folders, starting the
 * database - are not.
 */
@SpringBootTest
@ActiveProfiles(NimbusProfiles.WORKER)
// The marker says, before the context exists, that this run's database comes from
// outside. It has to: a standalone worker demands the connection the application
// wrote down, and the decision is taken by an EnvironmentPostProcessor - which runs
// before @ServiceConnection has registered anything to ask. The real connection is
// still the container's; nothing ever dials this value. What is asserted below is
// the composition of the worker profile, not how a datasource is bootstrapped.
@TestPropertySource(properties = { "spring.main.web-application-type=none",
		"SPRING_DATASOURCE_URL=managed-by-testcontainers" })
@Testcontainers
class WorkerProfileCompositionTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

	@Autowired
	private ApplicationContext context;

	@Test
	void hasEverythingItNeedsToClaimAndRunWork() {
		assertThat(context.getBeansOfType(WorkerLoop.class)).hasSize(1);
		assertThat(context.getBeansOfType(ExecutionDispatcher.class)).hasSize(1);
		assertThat(context.getBeansOfType(LeaseRenewer.class)).hasSize(1);
		assertThat(context.getBeansOfType(ExecutionJobHandler.class)).isNotEmpty();
	}

	/**
	 * Started headless, exactly as the supervisor will start it: the flag is an
	 * argument rather than a profile setting, because a group activates its
	 * members after itself and anything set for the worker profile would follow
	 * into app-worker-combined.
	 */
	@Test
	void servesNoScreens() {
		assertThat(context.getBeansOfType(ServletWebServerFactory.class)).isEmpty();
	}

	@Test
	void doesNotSuperviseTheDatabase() {
		assertThat(context.getBeansOfType(EmbeddedClusterService.class)).isEmpty();
	}

	@Test
	void doesNotWatchFolders() {
		assertThat(context.getBeansOfType(InventoryWatchService.class)).isEmpty();
	}

	/**
	 * Recovery answers for rows nobody holds a lease on, and the only thing
	 * keeping it from interrupting a live run is a set in the memory of whichever
	 * JVM asks. In a worker that set holds none of the application's work, so a
	 * worker running this - and one starts every time the supervisor replaces the
	 * previous - would mark an organization interrupted while it was still moving
	 * files. The bean not being here is what makes that impossible.
	 */
	@Test
	void doesNotRecoverExecutionsItCannotAnswerFor() {
		assertThat(context.getBeansOfType(StartupExecutionRecoveryListener.class)).isEmpty();
	}

	/**
	 * Both would mark every {@code RUNNING} fingerprint job failed - whoever owns
	 * it - and then start draining the same backlog beside the application, since
	 * the guard against a second drain is an {@code AtomicBoolean} one JVM cannot
	 * show another.
	 */
	@Test
	void startsNoFingerprintBacklog() {
		assertThat(context.getBeansOfType(FingerprintBacklogStartup.class)).isEmpty();
	}

	/**
	 * It sweeps where the encoding happens, and that is here now. A start is the
	 * one moment at which nothing can be in use: runners run before the ready event
	 * that starts the loop, so nothing has been claimed yet.
	 */
	@Test
	void sweepsTheConversionWorkspaceItEncodesInto() {
		assertThat(context.getBeansOfType(ConversionWorkspaceCleaner.class)).hasSize(1);
	}

	@Test
	void provisionsNoAccounts() {
		assertThat(context.getBeansOfType(DefaultUserInitializer.class)).isEmpty();
	}

	/**
	 * Ordered shutdown covers the application closing properly; this covers it
	 * being killed, which never gets to ask. Only where there is a parent to
	 * outlive - which is why the bean is {@code worker & !app}.
	 */
	@Test
	void watchesTheApplicationItWasStartedBy() {
		assertThat(context.getBeansOfType(ParentProcessWatch.class)).hasSize(1);
	}

	/**
	 * The tray is installed before there is a context to compose, so no assertion
	 * about beans could reach it. This asserts the decision itself, against the
	 * argument the supervisor actually passes.
	 */
	@Test
	void isRecognisedAsAWorkerBeforeSpringStarts() {
		assertThat(StartupRole.isStandaloneWorker(new String[] { "--spring.profiles.active=" + NimbusProfiles.WORKER }))
				.isTrue();
	}
}