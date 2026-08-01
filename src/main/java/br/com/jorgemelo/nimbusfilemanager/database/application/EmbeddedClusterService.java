package br.com.jorgemelo.nimbusfilemanager.database.application;

import static br.com.jorgemelo.nimbusfilemanager.database.application.constants.EmbeddedDatabaseConstants.SUPPORTED_MAJOR_VERSION;

import java.io.IOException;
import java.net.ServerSocket;

import br.com.jorgemelo.nimbusfilemanager.database.application.dto.ClusterConnection;
import br.com.jorgemelo.nimbusfilemanager.database.domain.enums.ClusterStartOutcome;
import br.com.jorgemelo.nimbusfilemanager.database.domain.enums.ClusterStopMode;
import lombok.extern.slf4j.Slf4j;

/**
 * Brings the cluster up and takes it down.
 *
 * <p>
 * Not a bean: the connection has to exist before the {@code DataSource} is
 * built, which is before there is a context to hold beans. It is constructed by
 * the bootstrap, used once at startup, and asked to stop on the way out.
 */
@Slf4j
public class EmbeddedClusterService {

	/**
	 * Each attempt costs a process start, and a machine where four consecutive free
	 * ports are all taken in the moment between choosing and binding has something
	 * else going on.
	 */
	private static final int START_ATTEMPTS = 4;

	private final ClusterLayout layout;
	private final ClusterPropertiesStore store;
	private final PostgresCommands commands;

	public EmbeddedClusterService(ClusterLayout layout, ClusterPropertiesStore store, PostgresCommands commands) {
		this.layout = layout;
		this.store = store;
		this.commands = commands;
	}

	/**
	 * The running cluster's connection, creating it on first run.
	 *
	 * @return how to reach it, or {@code null} when it could not be started - the
	 * caller falls back to the configured connection rather than taking the
	 * application down
	 */
	public ClusterConnection start() {
		try {
			if (!layout.initialized()) {
				return create();
			}

			return openExisting();
		} catch (IOException exception) {
			log.error("Could not start the embedded database", exception);

			return null;
		}
	}

	/**
	 * Refuses data written by another major version instead of letting the server
	 * refuse it later. PostgreSQL will not open a cluster across major versions,
	 * and the upgrade path is a decision to be taken deliberately - not something
	 * to trigger by installing a new build over an old one.
	 */
	private ClusterConnection openExisting() throws IOException {
		String major = layout.majorVersion();

		if (!SUPPORTED_MAJOR_VERSION.equals(major)) {
			log.error("The database at {} was written by PostgreSQL {}, and this build only opens {}. "
					+ "The cluster is left untouched.", layout.cluster(), major, SUPPORTED_MAJOR_VERSION);

			return null;
		}

		ClusterConnection stored = store.load();

		if (stored == null) {
			log.error("The database at {} exists but its port and password are missing from {}", layout.cluster(),
					layout.clusterProperties());

			return null;
		}

		return startOnAWorkingPort(stored);
	}

	private ClusterConnection create() throws IOException {
		BootstrapProgress.say("creating the database at " + layout.cluster());

		String password = store.generatePassword();

		if (!commands.initialize(password)) {
			return null;
		}

		ClusterConnection started = startOnAWorkingPort(new ClusterConnection(freePort(), password));

		if (started == null) {
			return null;
		}

		if (!commands.createDatabase(started.port(), password)) {
			return null;
		}

		return started;
	}

	/**
	 * Starts on the remembered port, and on another one when something took it
	 * first. The gap between finding a port free and the server binding it cannot
	 * be closed - only retried through, which beats refusing to start over a port
	 * number the user never chose and cannot see.
	 */
	private ClusterConnection startOnAWorkingPort(ClusterConnection connection) throws IOException {
		ClusterConnection attempt = connection;

		for (int remaining = START_ATTEMPTS; remaining > 0; remaining--) {
			ClusterStartOutcome outcome = commands.start(attempt.port());

			if (outcome == ClusterStartOutcome.STARTED) {
				store.save(attempt);

				BootstrapProgress.say("database ready on port " + attempt.port());

				return attempt;
			}

			if (outcome != ClusterStartOutcome.PORT_UNAVAILABLE) {
				return null;
			}

			log.warn("Port {} was taken before the database could bind it; trying another one", attempt.port());

			attempt = new ClusterConnection(freePort(), attempt.password());
		}

		log.error("Gave up starting the embedded database after {} attempts to find a free port", START_ATTEMPTS);

		return null;
	}

	/**
	 * Asks for a clean stop and escalates only if that does not finish. An
	 * immediate stop leaves the next start to replay the write-ahead log, which is
	 * safe but slower, so it is what happens after a clean stop was given its
	 * chance - never instead of one.
	 */
	public void stop() {
		if (commands.stop(ClusterStopMode.FAST)) {
			return;
		}

		log.warn("The embedded database did not stop cleanly in time; stopping it immediately. "
				+ "The next start will recover from the write-ahead log.");

		commands.stop(ClusterStopMode.IMMEDIATE);
	}

	/**
	 * A port the operating system says is free right now. Binding zero and reading
	 * back what was assigned is the only way to ask; keeping the socket open until
	 * the server starts is not possible, which is what the retry above is for.
	 */
	private int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}
}