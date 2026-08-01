package br.com.jorgemelo.nimbusfilemanager.database.infrastructure;

import static br.com.jorgemelo.nimbusfilemanager.database.application.constants.EmbeddedDatabaseConstants.DATABASE_NAME;
import static br.com.jorgemelo.nimbusfilemanager.database.application.constants.EmbeddedDatabaseConstants.DATABASE_USER;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import br.com.jorgemelo.nimbusfilemanager.database.application.ClusterLayout;
import br.com.jorgemelo.nimbusfilemanager.database.application.PostgresCommands;
import br.com.jorgemelo.nimbusfilemanager.database.domain.enums.ClusterStartOutcome;
import br.com.jorgemelo.nimbusfilemanager.database.domain.enums.ClusterStopMode;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the packaged PostgreSQL executables.
 *
 * <p>
 * Everything here is the mechanics of spawning a process and reading what it
 * printed; the decisions live in the service that calls it. The one piece of
 * interpretation that cannot move is telling a busy port from a real failure,
 * because that answer only exists in the server's own log text.
 */
@Slf4j
public class PostgresProcessRunner implements PostgresCommands {

	/** Long enough for a first start on a cold disk, short enough to notice. */
	private static final int START_TIMEOUT_SECONDS = 60;

	/**
	 * A clean stop rolls back open transactions and checkpoints first. Past this
	 * the caller escalates, so it is a budget for finishing rather than a guess
	 * at how long that takes.
	 */
	private static final int STOP_TIMEOUT_SECONDS = 30;

	private static final int COMMAND_TIMEOUT_SECONDS = 120;

	private static final String PG_CTL = "pg_ctl";

	private static final String PORT_TAKEN = "could not bind";
	private static final String ADDRESS_IN_USE = "address already in use";

	private final ClusterLayout layout;

	public PostgresProcessRunner(ClusterLayout layout) {
		this.layout = layout;
	}

	@Override
	public boolean initialize(String password) {
		Path passwordFile = null;

		try {
			Files.createDirectories(layout.cluster().getParent());

			// Beside the cluster rather than in the system temp folder: this file holds
			// the only credential the application has for its own database, and a
			// world-writable directory is readable by everything on the machine. It is
			// deleted as soon as initdb has read it either way.
			passwordFile = Files.createFile(layout.cluster().resolveSibling("initdb-password.txt"));

			Files.writeString(passwordFile, password, StandardCharsets.UTF_8);

			return run("initdb", List.of(layout.executable("initdb").toString(), "-D", layout.cluster().toString(),
					"-U", DATABASE_USER, "--pwfile=" + passwordFile, "-E", "UTF8", "--locale=C",
					"--auth-host=scram-sha-256", "--auth-local=trust"), COMMAND_TIMEOUT_SECONDS) == 0;
		} catch (IOException exception) {
			log.error("Could not create the embedded database cluster", exception);

			return false;
		} finally {
			deleteQuietly(passwordFile);
		}
	}

	/**
	 * Starts the server bound to loopback only. A database that exists to serve
	 * one desktop application has no reason to answer the network, and binding
	 * it there is what keeps the generated password from being the only thing
	 * between the catalog and the rest of the office.
	 */
	@Override
	public ClusterStartOutcome start(int port) {
		Path serverLog = layout.cluster().resolveSibling("postgres.log");

		int exit = run(PG_CTL,
				List.of(layout.executable(PG_CTL).toString(), "-D", layout.cluster().toString(), "-l",
						serverLog.toString(), "-w", "-t", Integer.toString(START_TIMEOUT_SECONDS), "-o",
						"-p " + port + " -c listen_addresses=127.0.0.1", "start"),
				START_TIMEOUT_SECONDS + 10);

		if (exit == 0) {
			return ClusterStartOutcome.STARTED;
		}

		return portWasTaken(serverLog) ? ClusterStartOutcome.PORT_UNAVAILABLE : ClusterStartOutcome.FAILED;
	}

	@Override
	public boolean stop(ClusterStopMode mode) {
		return run(PG_CTL, List.of(layout.executable(PG_CTL).toString(), "-D", layout.cluster().toString(), "-m",
				mode.argument(), "-w", "-t", Integer.toString(STOP_TIMEOUT_SECONDS), "stop"),
				STOP_TIMEOUT_SECONDS + 10) == 0;
	}

	@Override
	public boolean createDatabase(int port, String password) {
		return run("createdb", List.of(layout.executable("createdb").toString(), "-h", "127.0.0.1", "-p",
				Integer.toString(port), "-U", DATABASE_USER, DATABASE_NAME), COMMAND_TIMEOUT_SECONDS, password) == 0;
	}

	/**
	 * Whether the server refused the port rather than failing for some other
	 * reason. {@code pg_ctl} exits non-zero either way and says nothing useful
	 * itself - the reason is in the server log it redirected.
	 */
	private boolean portWasTaken(Path serverLog) {
		try {
			if (!Files.isRegularFile(serverLog)) {
				return false;
			}

			String text = Files.readString(serverLog, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);

			return text.contains(PORT_TAKEN) || text.contains(ADDRESS_IN_USE);
		} catch (IOException exception) {
			log.debug("Could not read the server log at {}", serverLog, exception);

			return false;
		}
	}

	private int run(String name, List<String> command, int timeoutSeconds) {
		return run(name, command, timeoutSeconds, null);
	}

	private int run(String name, List<String> command, int timeoutSeconds, String password) {
		Process process = null;

		try {
			ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);

			if (password != null) {
				builder.environment().put("PGPASSWORD", password);
			}

			process = builder.start();

			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

			if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
				process.destroyForcibly();

				log.error("{} did not finish within {} seconds", name, timeoutSeconds);

				return -1;
			}

			logOutcome(name, process.exitValue(), output);

			return process.exitValue();
		} catch (IOException exception) {
			log.error("Could not run {}", name, exception);

			return -1;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();

			log.warn("Interrupted while running {}", name, exception);

			return -1;
		} finally {
			if (process != null && process.isAlive()) {
				process.destroyForcibly();
			}
		}
	}

	private void logOutcome(String name, int exit, String output) {
		if (exit == 0) {
			log.debug("{} finished: {}", name, output.strip());

			return;
		}

		log.error("{} failed with exit code {}: {}", name, exit, output.strip());
	}

	private void deleteQuietly(Path path) {
		if (path == null) {
			return;
		}

		try {
			Files.deleteIfExists(path);
		} catch (IOException exception) {
			log.debug("Could not delete the temporary password file {}", path, exception);
		}
	}
}