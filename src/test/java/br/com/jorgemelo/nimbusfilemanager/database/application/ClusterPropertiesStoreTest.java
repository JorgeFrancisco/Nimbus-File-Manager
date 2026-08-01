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