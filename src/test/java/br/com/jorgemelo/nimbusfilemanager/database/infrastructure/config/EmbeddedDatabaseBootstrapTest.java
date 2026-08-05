package br.com.jorgemelo.nimbusfilemanager.database.infrastructure.config;

import java.io.IOException;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import br.com.jorgemelo.nimbusfilemanager.database.application.ClusterLayout;
import br.com.jorgemelo.nimbusfilemanager.database.application.ClusterPropertiesStore;
import br.com.jorgemelo.nimbusfilemanager.database.application.EmbeddedDatasourceProperties;
import br.com.jorgemelo.nimbusfilemanager.database.application.constants.EmbeddedDatabaseConstants;
import br.com.jorgemelo.nimbusfilemanager.database.application.constants.WorkspaceConstants;
import br.com.jorgemelo.nimbusfilemanager.database.application.dto.ClusterConnection;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.WorkspaceFolders;

/**
 * Who starts the cluster, and who is only allowed to connect to it.
 *
 * <p>
 * The product runs as two JVMs: the application owns the embedded PostgreSQL and
 * the worker runs beside it in a process of its own. That the worker must not
 * start a cluster is the oldest rule here and the least written down - it had no
 * test at all, which is how the other half of the contract came to be missing
 * without anything failing.
 *
 * <p>
 * Nothing here starts a real cluster. What is asserted is the decision: whether
 * this process is one that may create a database, and what it publishes for the
 * datasource to be built from.
 */
class EmbeddedDatabaseBootstrapTest {

	/**
	 * A worker is not a second application. Starting its own cluster would give it
	 * a database of its own - empty, on a port of its own - and the work it claimed
	 * would be invisible to the screens.
	 */
	@Test
	void aWorkerBesideTheApplicationStartsNoClusterOfItsOwn(@TempDir Path workspace) throws IOException {
		applicationStartedACluster(workspace, 6432, "generated-password");

		MockEnvironment environment = workerIn(workspace);

		new EmbeddedDatabaseBootstrap().postProcessEnvironment(environment, new SpringApplication());

		Assertions.assertThat(workspace.resolve(WorkspaceFolders.DATABASE).resolve("cluster"))
				.as("no cluster was created for a process that does not own one").doesNotExist();
	}

	/**
	 * It connects to the very cluster the application started, read from the file
	 * the application wrote in the workspace both processes resolve to.
	 */
	@Test
	void aWorkerConnectsToTheClusterTheApplicationStarted(@TempDir Path workspace) throws IOException {
		applicationStartedACluster(workspace, 6432, "generated-password");

		MockEnvironment environment = workerIn(workspace);

		new EmbeddedDatabaseBootstrap().postProcessEnvironment(environment, new SpringApplication());

		Assertions.assertThat(environment.getProperty(EmbeddedDatasourceProperties.URL)).contains(":6432/");
		Assertions.assertThat(environment.getProperty(EmbeddedDatasourceProperties.PASSWORD))
			.isEqualTo("generated-password");
	}

	/**
	 * With nothing written down it stops rather than guessing. The configured
	 * default names {@code localhost:5432}, which is often a real PostgreSQL
	 * holding something else entirely - connecting there would be worse than
	 * failing to start.
	 */
	@Test
	void aWorkerWithNothingToReadRefusesToStart(@TempDir Path workspace) {
		MockEnvironment environment = workerIn(workspace);

		EmbeddedDatabaseBootstrap bootstrap = new EmbeddedDatabaseBootstrap();

		SpringApplication application = new SpringApplication();

		Assertions.assertThatThrownBy(() -> bootstrap.postProcessEnvironment(environment, application))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("no embedded database connection");

		Assertions.assertThat(environment.getProperty(EmbeddedDatasourceProperties.URL))
			.as("and nothing was published for a datasource to be built from").isNull();
	}

	/**
	 * A database configured from outside is a deployment with no embedded cluster
	 * to attach to, so the file is not demanded of it.
	 */
	@Test
	void aWorkerToldWhereTheDatabaseIsDoesNotAskForTheFile(@TempDir Path workspace) {
		MockEnvironment environment = workerIn(workspace);

		environment.setProperty(EmbeddedDatabaseConstants.EXTERNAL_HOST_VARIABLE, "db.example.internal");

		new EmbeddedDatabaseBootstrap().postProcessEnvironment(environment, new SpringApplication());

		Assertions.assertThat(environment.getProperty(EmbeddedDatasourceProperties.URL)).isNull();
	}

	/**
	 * The application, in the same empty workspace, is the one that may create a
	 * cluster - so it is not refused for the absence the worker is refused for.
	 */
	@Test
	void theApplicationIsNotRefusedForAnAbsentConnection(@TempDir Path workspace) {
		MockEnvironment environment = new MockEnvironment();

		environment.setProperty(WorkspaceConstants.WORKSPACE_PROPERTY, workspace.toString());
		environment.setProperty(EmbeddedDatabaseConstants.EMBEDDED_PROPERTY, "false");
		environment.setActiveProfiles(NimbusProfiles.APP);

		new EmbeddedDatabaseBootstrap().postProcessEnvironment(environment, new SpringApplication());

		Assertions.assertThat(environment.getProperty("spring.datasource.url"))
				.as("nothing was published, and nothing was refused either").isNull();
	}

	/**
	 * Both roles in one JVM is the development shape, and there the application
	 * half owns the cluster: the worker rule must not catch it.
	 */
	@Test
	void bothRolesInOneProcessAreStillTheApplication(@TempDir Path workspace) {
		MockEnvironment environment = new MockEnvironment();

		environment.setProperty(WorkspaceConstants.WORKSPACE_PROPERTY, workspace.toString());
		environment.setProperty(EmbeddedDatabaseConstants.EMBEDDED_PROPERTY, "false");
		environment.setActiveProfiles(NimbusProfiles.APP, NimbusProfiles.WORKER);

		new EmbeddedDatabaseBootstrap().postProcessEnvironment(environment, new SpringApplication());

		Assertions.assertThat(environment.getProperty("spring.datasource.url")).isNull();
	}

	/** What the application leaves behind once its cluster is up. */
	private void applicationStartedACluster(Path workspace, int port, String password) throws IOException {
		new ClusterPropertiesStore(new ClusterLayout(workspace).clusterProperties())
			.save(new ClusterConnection(port, password));
	}

	/** A worker whose profile is the only thing this test needs to be true. */
	private MockEnvironment workerIn(Path workspace) {
		MockEnvironment environment = new MockEnvironment();

		environment.setProperty(WorkspaceConstants.WORKSPACE_PROPERTY, workspace.toString());
		environment.setActiveProfiles(NimbusProfiles.WORKER);

		return environment;
	}
}