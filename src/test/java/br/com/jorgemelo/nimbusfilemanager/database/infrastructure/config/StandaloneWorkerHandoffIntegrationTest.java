package br.com.jorgemelo.nimbusfilemanager.database.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

import br.com.jorgemelo.nimbusfilemanager.shared.TestPostgres;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.NimbusFileManagerApplication;
import br.com.jorgemelo.nimbusfilemanager.database.application.ClusterLayout;
import br.com.jorgemelo.nimbusfilemanager.database.application.ClusterPropertiesStore;
import br.com.jorgemelo.nimbusfilemanager.database.application.constants.EmbeddedDatabaseConstants;
import br.com.jorgemelo.nimbusfilemanager.database.application.constants.WorkspaceConstants;
import br.com.jorgemelo.nimbusfilemanager.database.application.dto.ClusterConnection;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.WorkspaceFolders;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * The handoff across a real process boundary.
 *
 * <p>
 * Everything before this proved the decision inside one JVM. What the product
 * actually does is start a second one, and the only thing that crosses is the
 * environment: the workspace. The worker has to find the database from that
 * alone - no URL on its command line, no password in its arguments, nothing
 * held in the application's memory.
 *
 * <p>
 * The test plays the application: it owns a PostgreSQL, has the schema, writes
 * the connection down where the application writes it, and puts a row in the
 * database. Then it launches a real JVM with the {@code worker} profile and
 * asks it what it found. A worker that reached some other PostgreSQL would boot
 * perfectly well and count zero, which is why the count is the assertion and
 * not the exit code.
 */
@SpringBootTest
@Testcontainers
class StandaloneWorkerHandoffIntegrationTest {

	private static final String INSERTED_BY_THE_APPLICATION = "the-row-the-application-inserted";

	private static final int STARTUP_GUARD_SECONDS = 180;

	/**
	 * Long enough to cover the second in which it used to end, with room to spare.
	 */
	private static final int STAYS_UP_SECONDS = 20;

	private static final String READY = "Worker ready";

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = TestPostgres.container()
			.withDatabaseName(EmbeddedDatabaseConstants.DATABASE_NAME)
			.withUsername(EmbeddedDatabaseConstants.DATABASE_USER).withPassword("handoff-password");

	@Autowired
	private ExecutionRepository executionRepository;

	@AfterEach
	void forgetEverything() {
		executionRepository.deleteAll();
	}

	/**
	 * The worker finds the database from the workspace alone, and it is the same
	 * database - it counts the row the application put there.
	 */
	@Test
	void aWorkerInItsOwnJvmReachesTheDatabaseTheApplicationIsUsing(@TempDir Path workspace) throws Exception {
		applicationWroteItsConnectionDown(workspace);

		theApplicationInsertedARow();

		ProbeResult probe = runWorkerIn(workspace);

		assertThat(probe.exitCode()).as("the worker started and stopped cleanly:\n" + probe.output()).isZero();
		assertThat(probe.reached()).as("it reached the port the application wrote down")
				.contains(":" + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/");
		assertThat(probe.counted()).as("and it is the same database, because the row is there").isEqualTo(1);
	}

	/**
	 * Nothing of the worker's own is created. A second cluster would be a second
	 * database, empty, and the work it claimed would be invisible to the screens.
	 */
	@Test
	void aWorkerInItsOwnJvmCreatesNoClusterOfItsOwn(@TempDir Path workspace) throws Exception {
		applicationWroteItsConnectionDown(workspace);

		String written = Files.readString(connectionFile(workspace));

		runWorkerIn(workspace);

		assertThat(workspace.resolve(WorkspaceFolders.DATABASE).resolve("cluster")).doesNotExist();
		assertThat(connectionFile(workspace)).as("and what the application wrote is untouched").hasContent(written);
	}

	/**
	 * The worker that follows a worker that ended reads the same file and reaches
	 * the same database. Nothing had to be kept in the application's memory to hand
	 * over again, which is the point of writing it down.
	 */
	@Test
	void aSecondWorkerAfterTheFirstOneEndedReachesTheSameDatabase(@TempDir Path workspace) throws Exception {
		applicationWroteItsConnectionDown(workspace);

		theApplicationInsertedARow();

		ProbeResult first = runWorkerIn(workspace);

		ProbeResult second = runWorkerIn(workspace);

		assertThat(first.exitCode()).isZero();
		assertThat(second.exitCode()).as("the restart started cleanly:\n" + second.output()).isZero();
		assertThat(second.reached()).as("same port").isEqualTo(first.reached());
		assertThat(second.counted()).as("same database, with the row still in it").isEqualTo(1);
	}

	/**
	 * With nothing written down it stops and says so. What it must not do is reach
	 * the configured default - {@code localhost:5432} is often a real PostgreSQL
	 * holding something else - or sit half-started.
	 */
	@Test
	void aWorkerWithNothingToReadStopsAndNamesTheFile(@TempDir Path workspace) throws Exception {
		ProbeResult probe = runWorkerIn(workspace);

		assertThat(probe.exitCode()).as("it did not start").isNotZero();
		assertThat(probe.output()).contains("no embedded database connection").contains(WorkspaceFolders.DATABASE);
		assertThat(probe.output()).as("and never reached anything").doesNotContain(StandaloneWorkerProbe.REACHED);
		assertThat(probe.output()).as("nor the password").doesNotContain("handoff-password");
	}

	/** A file that lost half of itself is refused the same way. */
	@Test
	void aWorkerWithHalfAConnectionStopsToo(@TempDir Path workspace) throws Exception {
		Path file = connectionFile(workspace);

		Files.createDirectories(file.getParent());
		Files.writeString(file, EmbeddedDatabaseConstants.PORT_KEY + "=6432");

		ProbeResult probe = runWorkerIn(workspace);

		assertThat(probe.exitCode()).isNotZero();
		assertThat(probe.output()).doesNotContain(StandaloneWorkerProbe.REACHED);
	}

	/**
	 * A worker stays up after its main method returns, and that is not obvious: it
	 * serves no port and installs no tray, its loop runs on virtual threads and
	 * every executor it has is a daemon one. Without something holding the JVM
	 * open, the last non-daemon thread was main itself - the installed worker
	 * announced it was ready and ended, with status 0, about a second later, eight
	 * times over, until the supervisor gave up and the product ran no background
	 * work at all.
	 *
	 * <p>
	 * Asserted as "did not exit while we watched", after waiting for the worker to
	 * say it is ready - a worker still starting would be alive for reasons that
	 * prove nothing.
	 */
	@Test
	void aWorkerInItsOwnJvmStaysUpOnceItIsReady(@TempDir Path workspace) throws Exception {
		applicationWroteItsConnectionDown(workspace);

		Path output = workspace.resolve("worker-output.log");

		Process worker = startRealWorkerIn(workspace, output);

		try {
			waitUntilReady(worker, output);

			assertThat(worker.waitFor(STAYS_UP_SECONDS, TimeUnit.SECONDS))
					.as("the worker ended on its own after becoming ready:\n" + Files.readString(output)).isFalse();
		} finally {
			worker.destroy();

			worker.waitFor(STARTUP_GUARD_SECONDS, TimeUnit.SECONDS);
		}
	}

	/**
	 * The real application under the worker profile, not a probe that reports and
	 * exits.
	 */
	private Process startRealWorkerIn(Path workspace, Path output) throws IOException {
		List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
				System.getProperty("java.class.path"), NimbusFileManagerApplication.class.getName(),
				"--spring.profiles.active=" + NimbusProfiles.WORKER, "--spring.main.web-application-type=none",
				"--spring.flyway.enabled=false");

		ProcessBuilder builder = new ProcessBuilder(command);

		builder.environment().put(WorkspaceConstants.WORKSPACE_ENVIRONMENT_VARIABLE, workspace.toString());
		builder.redirectErrorStream(true);
		builder.redirectOutput(output.toFile());

		return builder.start();
	}

	/**
	 * Waits for the worker to say it is claiming, rather than for a duration. The
	 * output is a file so nothing here has to drain a pipe the worker would
	 * otherwise block on.
	 */
	private void waitUntilReady(Process worker, Path output) throws Exception {
		try {
			await().atMost(Duration.ofSeconds(STARTUP_GUARD_SECONDS)).pollDelay(Duration.ZERO)
					.pollInterval(Duration.ofMillis(500)).until(() -> {
						// A worker that ended is not a worker that is late, and waiting out
						// the rest of the budget for one would only delay the same failure.
						if (!worker.isAlive()) {
							throw new AssertionError(
									"the worker ended before it was ready:\n" + Files.readString(output));
						}

						return Files.exists(output) && Files.readString(output).contains(READY);
					});
		} catch (ConditionTimeoutException _) {
			throw new AssertionError("the worker never became ready:\n" + Files.readString(output));
		}
	}

	/** What the application leaves behind once its cluster is up. */
	private void applicationWroteItsConnectionDown(Path workspace) throws IOException {
		new ClusterPropertiesStore(connectionFile(workspace)).save(new ClusterConnection(
				postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), postgres.getPassword()));
	}

	private Path connectionFile(Path workspace) {
		return new ClusterLayout(workspace).clusterProperties();
	}

	/**
	 * Finished rather than pending, and that is not incidental: a real worker
	 * claims a pending row, which is itself proof it is working against this
	 * database - but it rewrites the very column the count reads.
	 */
	private void theApplicationInsertedARow() {
		executionRepository.saveAndFlush(Execution.builder().executionType(ExecutionType.INVENTORY)
				.status(ExecutionStatus.FINISHED).recursive(false).executeFlag(true)
				.claimedBy(INSERTED_BY_THE_APPLICATION).claimCount(0).build());
	}

	/**
	 * A real second JVM, given nothing but the workspace.
	 *
	 * <p>
	 * The classpath is handed over because this runs from a directory of classes
	 * rather than from the installed jar - which is the branch
	 * {@code ProcessBuilderWorkerLauncher} takes here too. The workspace travels in
	 * the environment because that is what a child process inherits; a {@code -D}
	 * on this JVM would not reach it.
	 */
	private ProbeResult runWorkerIn(Path workspace) throws Exception {
		List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
				System.getProperty("java.class.path"), StandaloneWorkerProbe.class.getName(),
				"--spring.main.web-application-type=none", "--spring.flyway.enabled=false");

		ProcessBuilder builder = new ProcessBuilder(command);

		builder.environment().put(WorkspaceConstants.WORKSPACE_ENVIRONMENT_VARIABLE, workspace.toString());
		builder.redirectErrorStream(true);

		Process worker = builder.start();

		String output;

		try (var input = worker.getInputStream()) {
			output = new String(input.readAllBytes());
		}

		if (!worker.waitFor(STARTUP_GUARD_SECONDS, TimeUnit.SECONDS)) {
			worker.destroyForcibly();

			throw new AssertionError("the worker never finished:\n" + output);
		}

		return new ProbeResult(worker.exitValue(), output);
	}

	/** What the child said, and what it said it with. */
	private record ProbeResult(int exitCode, String output) {

		private String reached() {
			return lineAfter(StandaloneWorkerProbe.REACHED);
		}

		private int counted() {
			return Integer.parseInt(lineAfter(StandaloneWorkerProbe.COUNTED));
		}

		private String lineAfter(String marker) {
			return output.lines().filter(line -> line.contains(marker))
					.map(line -> line.substring(line.indexOf(marker) + marker.length()).trim()).findFirst()
					.orElseThrow(() -> new AssertionError("the worker never printed " + marker + ":\n" + output));
		}
	}
}