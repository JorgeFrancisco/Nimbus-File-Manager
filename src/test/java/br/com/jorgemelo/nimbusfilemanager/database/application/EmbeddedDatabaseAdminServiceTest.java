package br.com.jorgemelo.nimbusfilemanager.database.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.database.application.dto.EmbeddedDatabaseStatus;
import br.com.jorgemelo.nimbusfilemanager.database.domain.enums.ClusterStopMode;

/**
 * What the settings screen is told about the embedded database. The screen
 * renders these fields and decides nothing itself, so an install button offered
 * on a platform that cannot install, or a version shown for binaries that are
 * not there, would be a wrong answer with no second chance to catch it.
 */
class EmbeddedDatabaseAdminServiceTest {

	private static final String WINDOWS = "Windows 11";
	private static final String LINUX = "Linux";

	@Test
	void reportsAMissingServerWithNoVersion(@TempDir Path workspace, @TempDir Path server) {
		EmbeddedDatabaseStatus status = service(workspace, server, WINDOWS).status(false);

		Assertions.assertThat(status.installed()).isFalse();
		Assertions.assertThat(status.version()).isNull();
		Assertions.assertThat(status.installable()).isTrue();
	}

	@Test
	void reportsTheInstalledServerAndTheVersionItOpens(@TempDir Path workspace, @TempDir Path server)
			throws IOException {
		binaries(server);

		EmbeddedDatabaseStatus status = service(workspace, server, WINDOWS).status(true);

		Assertions.assertThat(status.installed()).isTrue();
		Assertions.assertThat(status.version()).isEqualTo("17");
		Assertions.assertThat(status.directory()).isNotBlank();
	}

	/**
	 * An update only reaches the running server on the next start, so a run
	 * being served by the embedded cluster is exactly the one that has to warn.
	 */
	@Test
	void warnsAboutTheRestartOnlyWhileTheEmbeddedClusterIsServing(@TempDir Path workspace, @TempDir Path server) {
		EmbeddedDatabaseAdminService service = service(workspace, server, WINDOWS);

		Assertions.assertThat(service.status(true).restartRequired()).isTrue();
		Assertions.assertThat(service.status(false).restartRequired()).isFalse();
	}

	/**
	 * Elsewhere the platform has its own PostgreSQL, and the button is not
	 * offered at all.
	 */
	@Test
	void offersNothingWherePostgresIsNotShipped(@TempDir Path workspace, @TempDir Path server) {
		Assertions.assertThat(service(workspace, server, LINUX).status(false).installable()).isFalse();
		Assertions.assertThat(service(workspace, server, LINUX).install()).isFalse();
		Assertions.assertThat(service(workspace, server, null).status(false).installable()).isFalse();
	}

	@Test
	void installsThroughTheSharedInstaller(@TempDir Path workspace, @TempDir Path server) {
		EmbeddedDatabaseAdminService service = new EmbeddedDatabaseAdminService(
				new ClusterLayout(workspace, server.resolve("bin")), _ -> null, WINDOWS);

		Assertions.assertThat(service.install()).isFalse();
	}

	/** The words pg_ctl expects; a typo here would only surface at shutdown. */
	@Test
	void namesTheStopModesTheWayPgCtlExpects() {
		Assertions.assertThat(ClusterStopMode.FAST.argument()).isEqualTo("fast");
		Assertions.assertThat(ClusterStopMode.IMMEDIATE.argument()).isEqualTo("immediate");
	}

	private void binaries(Path server) throws IOException {
		Path bin = Files.createDirectories(server.resolve("bin"));

		for (String name : new String[] { "pg_ctl.exe", "initdb.exe", "postgres.exe" }) {
			Files.writeString(bin.resolve(name), "x");
		}
	}

	private EmbeddedDatabaseAdminService service(Path workspace, Path server, String operatingSystem) {
		return new EmbeddedDatabaseAdminService(new ClusterLayout(workspace, server.resolve("bin")), _ -> null,
				operatingSystem);
	}
}