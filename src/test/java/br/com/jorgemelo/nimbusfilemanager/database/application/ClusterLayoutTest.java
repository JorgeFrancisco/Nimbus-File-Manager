package br.com.jorgemelo.nimbusfilemanager.database.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.shared.application.ToolsLocation;

/**
 * Where the cluster and its server live. Getting a path wrong here does not
 * fail loudly - it creates a second, empty cluster beside the real one and
 * opens an application with no catalog in it.
 */
class ClusterLayoutTest {

	@Test
	void keepsTheClusterUnderTheWorkspaceDatabaseFolder(@TempDir Path workspace) {
		ClusterLayout layout = new ClusterLayout(workspace);

		Assertions.assertThat(layout.cluster()).isEqualTo(workspace.resolve("database").resolve("cluster"));
		Assertions.assertThat(layout.clusterProperties())
				.isEqualTo(workspace.resolve("database").resolve("cluster.properties"));
	}

	/**
	 * The port and password sit beside the cluster, not inside it: initdb owns what
	 * is in PGDATA, and a stray file there is one more thing a later version of
	 * PostgreSQL could object to.
	 */
	@Test
	void keepsItsOwnFilesOutOfTheDataFolder(@TempDir Path workspace) {
		ClusterLayout layout = new ClusterLayout(workspace);

		Assertions.assertThat(layout.clusterProperties().startsWith(layout.cluster())).isFalse();
	}

	@Test
	void reportsAnUninitializedClusterAsSuch(@TempDir Path workspace) throws IOException {
		ClusterLayout layout = new ClusterLayout(workspace);

		Assertions.assertThat(layout.initialized()).isFalse();
		Assertions.assertThat(layout.majorVersion()).isNull();
	}

	@Test
	void readsTheMajorVersionTheDataWasWrittenBy(@TempDir Path workspace) throws IOException {
		ClusterLayout layout = new ClusterLayout(workspace);

		Files.createDirectories(layout.cluster());
		Files.writeString(layout.cluster().resolve("PG_VERSION"), "17\n");

		Assertions.assertThat(layout.initialized()).isTrue();
		Assertions.assertThat(layout.majorVersion()).isEqualTo("17");
	}

	/**
	 * Where a tool lives is {@link ToolsLocation}'s answer, tested there; this
	 * pins that the cluster asks for the PostgreSQL folder and appends the
	 * executable it wants, rather than building a path of its own.
	 */
	@Test
	void asksTheSharedToolsLocationForItsServer(@TempDir Path workspace) {
		Assertions.assertThat(new ClusterLayout(workspace).executable("pg_ctl"))
				.isEqualTo(ToolsLocation.of(workspace, "postgresql").resolve("pg_ctl.exe"));
	}

	@Test
	void findsThePackagedServerOnlyWhenEveryExecutableIsThere(@TempDir Path workspace, @TempDir Path binaries)
			throws IOException {
		ClusterLayout layout = new ClusterLayout(workspace, binaries);

		Assertions.assertThat(layout.executable("pg_ctl")).isEqualTo(binaries.resolve("pg_ctl.exe"));
		Assertions.assertThat(layout.binariesPresent()).isFalse();

		Files.writeString(binaries.resolve("pg_ctl.exe"), "x");
		Files.writeString(binaries.resolve("initdb.exe"), "x");

		Assertions.assertThat(layout.binariesPresent()).isFalse();

		Files.writeString(binaries.resolve("postgres.exe"), "x");

		Assertions.assertThat(layout.binariesPresent()).isTrue();
	}
}