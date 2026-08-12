package br.com.jorgemelo.nimbusfilemanager.database.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.database.application.dto.ClusterConnection;

/**
 * What has to survive a restart. The password is set once when the cluster is
 * created and can never be recomputed: losing it locks the application out of
 * its own catalog, with the data intact and unreachable.
 */
class ClusterPropertiesStoreTest {

	@Test
	void remembersThePortAndPasswordAcrossRuns(@TempDir Path workspace) throws IOException {
		ClusterPropertiesStore store = new ClusterPropertiesStore(
				workspace.resolve("database").resolve("cluster.properties"));

		ClusterConnection connection = new ClusterConnection(6432, "generated-password");

		store.save(connection);

		Assertions.assertThat(store.load()).isEqualTo(connection);
	}

	/**
	 * What a process that does not own the cluster is entitled to demand.
	 *
	 * <p>
	 * The worker runs in a JVM of its own and never starts a cluster, so the file
	 * the application wrote is the only place its connection can come from.
	 */
	@Test
	void handsOverTheConnectionTheApplicationWroteDown(@TempDir Path workspace) throws IOException {
		ClusterPropertiesStore store = new ClusterPropertiesStore(workspace.resolve("cluster.properties"));

		ClusterConnection connection = new ClusterConnection(6432, "generated-password");

		store.save(connection);

		Assertions.assertThat(store.require()).isEqualTo(connection);
	}

	/**
	 * No file is an ordinary answer to the application and an impossible one to a
	 * worker: there is nothing for it to work against, and the configured default
	 * points at whatever PostgreSQL happens to be on 5432.
	 */
	@Test
	void refusesToGuessWhenTheApplicationWroteNothing(@TempDir Path workspace) {
		Path file = workspace.resolve("cluster.properties");

		ClusterPropertiesStore store = new ClusterPropertiesStore(file);

		Assertions.assertThatThrownBy(store::require)
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("no embedded database connection")
			.hasMessageContaining(file.toString());
	}

	/** Half a connection is no connection, and saying so beats guessing. */
	@Test
	void refusesToGuessWhenOnlyHalfWasWrittenDown(@TempDir Path workspace) throws IOException {
		Path file = workspace.resolve("cluster.properties");

		Files.writeString(file, "port=6432");

		ClusterPropertiesStore store = new ClusterPropertiesStore(file);

		Assertions.assertThatThrownBy(store::require)
			.isInstanceOf(IllegalStateException.class);
	}

	/** And a port that is not a number is a broken file, not an absent one. */
	@Test
	void refusesAPortThatIsNotAPort(@TempDir Path workspace) throws IOException {
		Path file = workspace.resolve("cluster.properties");

		Files.writeString(file, "port=not-a-port" + System.lineSeparator() + "password=secret");

		ClusterPropertiesStore store = new ClusterPropertiesStore(file);

		Assertions.assertThatThrownBy(store::require)
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("usable port");
	}

	/** Nothing stored yet is not an error - it is a cluster about to be created. */
	@Test
	void answersNothingBeforeTheFirstRun(@TempDir Path workspace) throws IOException {
		Assertions.assertThat(new ClusterPropertiesStore(workspace.resolve("cluster.properties")).load()).isNull();
	}

	/**
	 * A file that lost one of the two is as useless as no file: reporting it as
	 * absent sends the caller down the path that says so, instead of into a
	 * connection attempt with half a credential.
	 */
	@Test
	void treatsAnIncompleteFileAsNothingStored(@TempDir Path workspace) throws IOException {
		Path file = workspace.resolve("cluster.properties");

		Files.writeString(file, "port=6432");

		Assertions.assertThat(new ClusterPropertiesStore(file).load()).isNull();
	}

	/** Either half missing is the same answer: there is nothing usable here. */
	@Test
	void treatsAFileMissingThePortAsNothingStored(@TempDir Path workspace) throws IOException {
		Path file = workspace.resolve("cluster.properties");

		Files.writeString(file, "password=generated");

		Assertions.assertThat(new ClusterPropertiesStore(file).load()).isNull();
	}

	/**
	 * Generated rather than shipped: a constant in the source would be the same
	 * password on every installation, and the only thing guarding a server that
	 * listens on loopback would be public knowledge.
	 */
	@Test
	void generatesADifferentPasswordEveryTime(@TempDir Path workspace) {
		ClusterPropertiesStore store = new ClusterPropertiesStore(workspace.resolve("cluster.properties"));

		String first = store.generatePassword();

		Assertions.assertThat(first).hasSizeGreaterThanOrEqualTo(32).isNotEqualTo(store.generatePassword());
	}
}