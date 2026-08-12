package br.com.jorgemelo.nimbusfilemanager.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

import br.com.jorgemelo.nimbusfilemanager.shared.TestPostgres;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintBacklogStartup;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.ConversionWorkspaceCleaner;
import br.com.jorgemelo.nimbusfilemanager.execution.application.StartupExecutionRecoveryListener;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.InventoryWatchService;
import br.com.jorgemelo.nimbusfilemanager.security.application.DefaultUserInitializer;
import br.com.jorgemelo.nimbusfilemanager.shared.application.StartupRole;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.worker.application.ExecutionDispatcher;
import br.com.jorgemelo.nimbusfilemanager.worker.application.LeaseRenewer;
import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerLoop;

/**
 * The application role, which is what an unqualified start gets.
 *
 * <p>
 * The half that matters here is the absence: an application that also claimed
 * from the queue would be doing the work in the very heap the split exists to
 * protect, and nobody would notice until a conversion made the screens crawl.
 */
@SpringBootTest
@ActiveProfiles(NimbusProfiles.APP)
@Testcontainers
class AppProfileCompositionTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = TestPostgres.container();

	@Autowired
	private ApplicationContext context;

	@Test
	void watchesFoldersAndProducesWork() {
		assertThat(context.getBeansOfType(InventoryWatchService.class)).hasSize(1);
	}

	@Test
	void claimsNothingFromTheQueue() {
		assertThat(context.getBeansOfType(WorkerLoop.class)).isEmpty();
		assertThat(context.getBeansOfType(ExecutionDispatcher.class)).isEmpty();
		assertThat(context.getBeansOfType(LeaseRenewer.class)).isEmpty();
	}

	/**
	 * The default role. An installed copy, every existing launcher and the MSI
	 * start the jar with no profile argument at all, and they must keep getting
	 * the application.
	 */
	@Test
	void isWhatAnUnqualifiedStartGets() {
		assertThat(context.getEnvironment().getDefaultProfiles()).contains(NimbusProfiles.APP);
	}

	/**
	 * Everything a worker must not start still starts here, which is the other
	 * half of confining them to this role: moving them out of the worker is only
	 * correct if nothing was lost on the way.
	 */
	@Test
	void keepsTheStartupWorkThatBelongsToIt() {
		assertThat(context.getBeansOfType(StartupExecutionRecoveryListener.class)).hasSize(1);
		assertThat(context.getBeansOfType(FingerprintBacklogStartup.class)).hasSize(1);
		assertThat(context.getBeansOfType(DefaultUserInitializer.class)).hasSize(1);
	}

	/**
	 * And not the conversion workspace, which went with the encoding: sweeping it
	 * here would empty the folder the worker is encoding into.
	 */
	@Test
	void sweepsNoConversionWorkspace() {
		assertThat(context.getBeansOfType(ConversionWorkspaceCleaner.class)).isEmpty();
	}

	/** And the tray, decided before any of these beans exist. */
	@Test
	void isNotRecognisedAsAWorkerBeforeSpringStarts() {
		assertThat(StartupRole.isStandaloneWorker(new String[] { "--spring.profiles.active=" + NimbusProfiles.APP }))
				.isFalse();

		assertThat(StartupRole.isStandaloneWorker(new String[0])).isFalse();
	}
}