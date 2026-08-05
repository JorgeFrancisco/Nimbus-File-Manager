package br.com.jorgemelo.nimbusfilemanager.database.infrastructure.config;

import static br.com.jorgemelo.nimbusfilemanager.database.application.constants.EmbeddedDatabaseConstants.AUTO_INSTALL_PROPERTY;
import static br.com.jorgemelo.nimbusfilemanager.database.application.constants.EmbeddedDatabaseConstants.DOWNLOAD_URL_PROPERTY;
import static br.com.jorgemelo.nimbusfilemanager.database.application.constants.EmbeddedDatabaseConstants.EMBEDDED_PROPERTY;
import static br.com.jorgemelo.nimbusfilemanager.database.application.constants.EmbeddedDatabaseConstants.EXTERNAL_HOST_VARIABLE;
import static br.com.jorgemelo.nimbusfilemanager.database.application.constants.EmbeddedDatabaseConstants.EXTERNAL_URL_VARIABLE;
import static br.com.jorgemelo.nimbusfilemanager.database.application.constants.WorkspaceConstants.WORKSPACE_PROPERTY;
import static br.com.jorgemelo.nimbusfilemanager.database.domain.enums.EmbeddedDatabaseDecision.BINARIES_MISSING;

import java.nio.file.Path;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.MapPropertySource;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.database.application.BootstrapProgress;
import br.com.jorgemelo.nimbusfilemanager.database.application.ClusterLayout;
import br.com.jorgemelo.nimbusfilemanager.database.application.ClusterPropertiesStore;
import br.com.jorgemelo.nimbusfilemanager.database.application.EmbeddedClusterService;
import br.com.jorgemelo.nimbusfilemanager.database.application.EmbeddedDatabaseActivation;
import br.com.jorgemelo.nimbusfilemanager.database.application.EmbeddedDatabaseInstaller;
import br.com.jorgemelo.nimbusfilemanager.database.application.EmbeddedDatasourceProperties;
import br.com.jorgemelo.nimbusfilemanager.database.application.dto.ClusterConnection;
import br.com.jorgemelo.nimbusfilemanager.database.domain.enums.EmbeddedDatabaseDecision;
import br.com.jorgemelo.nimbusfilemanager.database.infrastructure.PostgresBuildSource;
import br.com.jorgemelo.nimbusfilemanager.database.infrastructure.PostgresProcessRunner;
import br.com.jorgemelo.nimbusfilemanager.shared.application.WorkspaceLocation;
import lombok.extern.slf4j.Slf4j;

/**
 * Starts the packaged PostgreSQL, when this run is one that should, and
 * publishes how to reach it.
 *
 * <p>
 * An {@code EnvironmentPostProcessor} because the connection has to exist
 * before the {@code DataSource} is built, and that happens before there is a
 * context. Whether to do it at all is decided by
 * {@link EmbeddedDatabaseActivation}, which is the only place that knows the
 * order of the signals; this class reads them and does as it is told.
 *
 * <p>
 * The property source is added first, so it wins over the packaged defaults -
 * those exist for a developer's own server and would otherwise send the
 * application to port 5432 while its own cluster listens elsewhere.
 */
@Slf4j
public class EmbeddedDatabaseBootstrap implements EnvironmentPostProcessor {

	private static final String SOURCE_NAME = "nimbusFileManagerEmbeddedDatabase";

	/**
	 * The running cluster, for the listener that stops it. A static field because
	 * the object is created before the context exists and has to be reachable from
	 * a listener that the context creates - the same bridge the application already
	 * uses where framework callbacks cannot be injected.
	 */
	private static EmbeddedClusterService running;

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (isStandaloneWorker(environment)) {
			return;
		}

		ClusterLayout layout = new ClusterLayout(workspace(environment));

		EmbeddedDatabaseDecision decision = decide(environment, layout);

		if (decision == BINARIES_MISSING && autoInstall(environment) && install(environment, layout)) {
			decision = decide(environment, layout);
		}

		if (!decision.active()) {
			BootstrapProgress.say("embedded database not started: " + decision);

			return;
		}

		EmbeddedClusterService service = new EmbeddedClusterService(layout,
				new ClusterPropertiesStore(layout.clusterProperties()), new PostgresProcessRunner(layout));

		ClusterConnection connection = service.start();

		if (connection == null) {
			BootstrapProgress.say("could not start the embedded database; using the configured connection");

			return;
		}

		remember(service);

		// After the context, not with it. Spring Boot runs these handlers once every
		// context has been closed, so by the time the server is asked to stop, Tomcat
		// has drained and the connection pool is shut. Stopping it from a
		// ContextClosedEvent looked equivalent and was not: that event fires at the
		// start of the close, and the first Ctrl+C proved it - PostgreSQL terminated
		// live connections, and a normal shutdown printed a wall of stack traces from
		// work that was still running.
		SpringApplication.getShutdownHandlers().add(EmbeddedDatabaseBootstrap::stopRunning);

		environment.getPropertySources()
				.addFirst(new MapPropertySource(SOURCE_NAME, EmbeddedDatasourceProperties.from(connection)));
	}

	private static void stopRunning() {
		EmbeddedClusterService service = running;

		if (service != null) {
			service.stop();
		}
	}

	private static void remember(EmbeddedClusterService service) {
		running = service;
	}

	/** Whether the embedded cluster is the one serving this run. */
	public static boolean serving() {
		return running != null;
	}

	/**
	 * Whether this process is a worker on its own, and therefore not the one that
	 * runs the database.
	 *
	 * <p>
	 * A worker connects to the cluster the application already supervises - two
	 * processes starting the same data directory would be a second postmaster on
	 * the same files. The check lives here, alone, because this runs before the
	 * context exists and no bean can guard it; every other role difference is a
	 * profile on a component.
	 *
	 * <p>
	 * Asked as "am I a worker?" rather than "am I the application?" on purpose.
	 * This runs more than once and, early on, runs before profiles are resolved -
	 * so the application's absence is not evidence of anything, while the worker
	 * profile's presence is. Getting that backwards made an ordinary start
	 * announce, repeatedly, that it was not starting the database.
	 */
	private boolean isStandaloneWorker(ConfigurableEnvironment environment) {
		return environment.acceptsProfiles(Profiles.of(NimbusProfiles.WORKER + " & !" + NimbusProfiles.APP));
	}

	private EmbeddedDatabaseDecision decide(ConfigurableEnvironment environment, ClusterLayout layout) {
		return EmbeddedDatabaseActivation.decide(environment.getProperty(EMBEDDED_PROPERTY),
				externalDatabase(environment), System.getProperty("os.name"), layout.binariesPresent());
	}

	/**
	 * Fetches the server on the first start that finds it missing.
	 *
	 * <p>
	 * Unlike ffmpeg, which downloads once the application is up and running without
	 * it, this cannot wait for the context: there is no application without a
	 * database. So the first start of an installed copy pays for the download
	 * before it opens, and the settings screen is where a later one is asked for.
	 */
	private boolean install(ConfigurableEnvironment environment, ClusterLayout layout) {
		BootstrapProgress.say("the database server is not installed yet");

		return new EmbeddedDatabaseInstaller(layout,
				new PostgresBuildSource(environment.getProperty(DOWNLOAD_URL_PROPERTY))).install();
	}

	private boolean autoInstall(ConfigurableEnvironment environment) {
		return environment.getProperty(AUTO_INSTALL_PROPERTY, Boolean.class, Boolean.TRUE);
	}

	/**
	 * Read from the property when the workspace listener has already published it,
	 * and resolved directly otherwise, so this does not depend on which of the two
	 * runs first.
	 */
	private Path workspace(ConfigurableEnvironment environment) {
		String configured = environment.getProperty(WORKSPACE_PROPERTY);

		return Path.of(configured == null || configured.isBlank() ? WorkspaceLocation.resolve() : configured)
				.toAbsolutePath().normalize();
	}

	/**
	 * Only what somebody set themselves counts. The packaged properties always
	 * define a datasource url, so the environment variables are the ones that
	 * distinguish a deliberate choice from a default.
	 */
	private String externalDatabase(ConfigurableEnvironment environment) {
		String host = environment.getProperty(EXTERNAL_HOST_VARIABLE);

		return host == null || host.isBlank() ? environment.getProperty(EXTERNAL_URL_VARIABLE) : host;
	}
}