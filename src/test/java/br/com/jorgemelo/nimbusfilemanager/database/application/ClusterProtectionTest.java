package br.com.jorgemelo.nimbusfilemanager.database.application;

import static br.com.jorgemelo.nimbusfilemanager.database.application.constants.EmbeddedDatabaseConstants.VERSION_FILE;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The guard that stands between a cleanup routine and the catalog. Everything
 * else under the workspace is regenerated on demand - thumbnails, temp files,
 * downloaded datasets - so the routines that clear it are written to be
 * fearless. The cluster is the one thing in there that no backup can bring back
 * if the wrong path reaches one of them.
 */
class ClusterProtectionTest {

	@Test
	void recognizesAClusterByItsVersionFile(@TempDir Path workspace) throws IOException {
		Path cluster = Files.createDirectories(workspace.resolve("database").resolve("cluster"));

		Files.writeString(cluster.resolve(VERSION_FILE), "17");

		Assertions.assertThat(ClusterProtection.holdsCluster(cluster)).isTrue();
	}

	/**
	 * Deleting a parent destroys the cluster as thoroughly as deleting the cluster:
	 * a routine handed the workspace root has to be refused too.
	 */
	@Test
	void protectsEveryFolderTheClusterLivesUnder(@TempDir Path workspace) throws IOException {
		Path cluster = Files.createDirectories(workspace.resolve("database").resolve("cluster"));

		Files.writeString(cluster.resolve(VERSION_FILE), "17");

		Assertions.assertThat(ClusterProtection.holdsCluster(workspace)).isTrue();
		Assertions.assertThat(ClusterProtection.holdsCluster(workspace.resolve("database"))).isTrue();
	}

	@Test
	void leavesOrdinaryFoldersAlone(@TempDir Path workspace) throws IOException {
		Path cache = Files.createDirectories(workspace.resolve("cache").resolve("thumbnails"));

		Files.writeString(cache.resolve("thumb.jpg"), "not a cluster");

		Assertions.assertThat(ClusterProtection.holdsCluster(cache)).isFalse();
	}

	/** A folder that does not exist holds nothing, and neither does a file. */
	@Test
	void answersNoForWhatIsNotADirectory(@TempDir Path workspace) throws IOException {
		Path file = Files.writeString(workspace.resolve("notes.txt"), "text");

		Assertions.assertThat(ClusterProtection.holdsCluster(workspace.resolve("absent"))).isFalse();
		Assertions.assertThat(ClusterProtection.holdsCluster(file)).isFalse();
		Assertions.assertThat(ClusterProtection.holdsCluster(null)).isFalse();
	}

	/**
	 * A directory named like the marker is not one: the check is for a file, so a
	 * folder called {@code PG_VERSION} must not lock a tree that has no cluster.
	 */
	@Test
	void doesNotMistakeADirectoryForTheVersionFile(@TempDir Path workspace) throws IOException {
		Files.createDirectories(workspace.resolve("temp").resolve(VERSION_FILE));

		Assertions.assertThat(ClusterProtection.holdsCluster(workspace)).isFalse();
	}
}