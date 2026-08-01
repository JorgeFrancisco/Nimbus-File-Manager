package br.com.jorgemelo.nimbusfilemanager.database.application;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.jorgemelo.nimbusfilemanager.database.application.dto.ClusterConnection;
import br.com.jorgemelo.nimbusfilemanager.database.domain.enums.ClusterStartOutcome;
import br.com.jorgemelo.nimbusfilemanager.database.domain.enums.ClusterStopMode;

/**
 * Bringing the catalog's database up and down. The cases here are the ones that
 * decide whether an installed copy opens at all: a port taken between choosing
 * it and binding it, data written by a version this build cannot read, and a
 * server that will not stop when asked politely.
 */
@ExtendWith(MockitoExtension.class)
class EmbeddedClusterServiceTest {

	@TempDir
	Path workspace;

	@Mock
	private PostgresCommands commands;

	@Test
	void createsTheClusterOnAFirstRunAndRemembersHowToReachIt() throws IOException {
		when(commands.initialize(anyString())).thenReturn(true);
		when(commands.start(anyInt())).thenReturn(ClusterStartOutcome.STARTED);
		when(commands.createDatabase(anyInt(), anyString())).thenReturn(true);

		ClusterConnection connection = service().start();

		Assertions.assertThat(connection).isNotNull();
		Assertions.assertThat(connection.port()).isPositive();

		Assertions.assertThat(store().load()).isEqualTo(connection);
	}

	/**
	 * The gap between finding a port free and the server binding it cannot be
	 * closed, so it is retried through. Refusing to start over a port number the
	 * user never chose would be a failure with nothing for them to act on.
	 */
	@Test
	void triesAnotherPortWhenSomethingTookTheFirstOne() {
		when(commands.initialize(anyString())).thenReturn(true);
		when(commands.start(anyInt())).thenReturn(ClusterStartOutcome.PORT_UNAVAILABLE)
				.thenReturn(ClusterStartOutcome.STARTED);
		when(commands.createDatabase(anyInt(), anyString())).thenReturn(true);

		Assertions.assertThat(service().start()).isNotNull();

		verify(commands, times(2)).start(anyInt());
	}

	/** Any other failure is not about the port, and retrying only repeats it. */
	@Test
	void stopsTryingWhenTheFailureIsNotAboutThePort() {
		when(commands.initialize(anyString())).thenReturn(true);
		when(commands.start(anyInt())).thenReturn(ClusterStartOutcome.FAILED);

		Assertions.assertThat(service().start()).isNull();

		verify(commands, never()).createDatabase(anyInt(), anyString());
	}

	@Test
	void reopensAnExistingClusterOnItsRememberedPort() throws IOException {
		existingCluster("17");

		ClusterConnection stored = new ClusterConnection(6432, "kept-password");

		store().save(stored);

		when(commands.start(6432)).thenReturn(ClusterStartOutcome.STARTED);

		Assertions.assertThat(service().start()).isEqualTo(stored);

		verify(commands, never()).initialize(anyString());
	}

	/**
	 * PostgreSQL will not open data written by another major version, and the
	 * upgrade is a decision to take deliberately rather than to trigger by
	 * installing a new build. Refusing leaves the old cluster exactly where it is,
	 * which is what makes the decision still available afterwards.
	 */
	@Test
	void refusesDataWrittenByAnotherMajorVersionWithoutTouchingIt() throws IOException {
		Path cluster = existingCluster("16");

		store().save(new ClusterConnection(6432, "kept-password"));

		Assertions.assertThat(service().start()).isNull();

		verify(commands, never()).start(anyInt());
		verify(commands, never()).initialize(anyString());

		Assertions.assertThat(Files.readString(cluster.resolve("PG_VERSION"))).isEqualTo("16");
	}

	/**
	 * A cluster whose password was lost cannot be reached, and saying so beats
	 * failing to connect with no explanation.
	 */
	@Test
	void refusesAnExistingClusterWhoseCredentialsAreGone() throws IOException {
		existingCluster("17");

		Assertions.assertThat(service().start()).isNull();

		verify(commands, never()).start(anyInt());
	}

	@Test
	void stopsCleanlyWhenTheServerCooperates() {
		when(commands.stop(ClusterStopMode.FAST)).thenReturn(true);

		service().stop();

		verify(commands, never()).stop(ClusterStopMode.IMMEDIATE);
	}

	/**
	 * An immediate stop leaves the next start replaying the write-ahead log, so it
	 * only happens after a clean stop has had its chance - never instead of one.
	 */
	@Test
	void escalatesOnlyAfterACleanStopHasFailed() {
		when(commands.stop(ClusterStopMode.FAST)).thenReturn(false);
		when(commands.stop(ClusterStopMode.IMMEDIATE)).thenReturn(true);

		service().stop();

		verify(commands).stop(ClusterStopMode.FAST);
		verify(commands).stop(ClusterStopMode.IMMEDIATE);
	}

	/** A cluster that could not be created is not one to start talking to. */
	@Test
	void stopsWhenTheClusterCouldNotBeCreated() {
		when(commands.initialize(anyString())).thenReturn(false);

		Assertions.assertThat(service().start()).isNull();

		verify(commands, never()).start(anyInt());
	}

	/**
	 * A server running without the application database is worse than none: the
	 * connection would be published and every query would fail on a database that
	 * is not there.
	 */
	@Test
	void stopsWhenTheDatabaseCouldNotBeCreated() {
		when(commands.initialize(anyString())).thenReturn(true);
		when(commands.start(anyInt())).thenReturn(ClusterStartOutcome.STARTED);
		when(commands.createDatabase(anyInt(), anyString())).thenReturn(false);

		Assertions.assertThat(service().start()).isNull();
	}

	/**
	 * Retrying is for the port that was taken in between. A machine where every
	 * port chosen is gone before it can be bound has something else going on, and
	 * trying forever would hang the start instead of reporting it.
	 */
	@Test
	void givesUpAfterTooManyPortsAreTakenInARow() {
		when(commands.initialize(anyString())).thenReturn(true);
		when(commands.start(anyInt())).thenReturn(ClusterStartOutcome.PORT_UNAVAILABLE);

		Assertions.assertThat(service().start()).isNull();

		verify(commands, times(4)).start(anyInt());
	}

	private Path existingCluster(String majorVersion) throws IOException {
		Path cluster = Files.createDirectories(workspace.resolve("database").resolve("cluster"));

		Files.writeString(cluster.resolve("PG_VERSION"), majorVersion);

		return cluster;
	}

	private ClusterPropertiesStore store() {
		return new ClusterPropertiesStore(layout().clusterProperties());
	}

	private ClusterLayout layout() {
		return new ClusterLayout(workspace);
	}

	private EmbeddedClusterService service() {
		return new EmbeddedClusterService(layout(), store(), commands);
	}
}