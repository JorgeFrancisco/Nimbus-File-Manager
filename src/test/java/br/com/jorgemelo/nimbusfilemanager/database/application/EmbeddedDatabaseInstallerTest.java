package br.com.jorgemelo.nimbusfilemanager.database.application;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unpacking the server nobody should have to fetch by hand. The published
 * archive is most of a development distribution: keeping all of it would turn a
 * download into a few hundred MB of headers and symbols that never run.
 */
class EmbeddedDatabaseInstallerTest {

	@Test
	void keepsWhatTheServerNeedsAndLeavesTheRestBehind(@TempDir Path workspace, @TempDir Path server)
			throws IOException {
		Path archive = archive(server.resolve("postgresql.zip"), "pgsql/bin/postgres.exe", "pgsql/bin/initdb.exe",
				"pgsql/bin/pg_ctl.exe", "pgsql/lib/libpq.dll", "pgsql/share/postgres.bki", "pgsql/include/libpq-fe.h",
				"pgsql/doc/README");

		ClusterLayout layout = new ClusterLayout(workspace, server.resolve("bin"));

		Assertions.assertThat(installer(layout, archive).install()).isTrue();

		Assertions.assertThat(server.resolve("bin/postgres.exe")).exists();
		Assertions.assertThat(server.resolve("lib/libpq.dll")).exists();
		Assertions.assertThat(server.resolve("share/postgres.bki")).exists();

		Assertions.assertThat(server.resolve("include")).doesNotExist();
		Assertions.assertThat(server.resolve("doc")).doesNotExist();
	}

	/**
	 * An archive entry is input like any other. A name that climbs out of the
	 * install folder has to be ignored rather than followed - this one would
	 * otherwise drop a file straight into the workspace.
	 */
	@Test
	void ignoresAnEntryThatWouldBeWrittenOutsideTheInstallFolder(@TempDir Path workspace, @TempDir Path server)
			throws IOException {
		Path archive = archive(server.resolve("postgresql.zip"), "pgsql/bin/postgres.exe", "pgsql/bin/initdb.exe",
				"pgsql/bin/pg_ctl.exe", "pgsql/bin/../../../escaped.txt");

		ClusterLayout layout = new ClusterLayout(workspace, server.resolve("bin"));

		Assertions.assertThat(installer(layout, archive).install()).isTrue();

		Assertions.assertThat(server.getParent().resolve("escaped.txt")).doesNotExist();
	}

	/** A download that never arrived is not an install, and says so. */
	@Test
	void reportsFailureWhenTheArchiveCouldNotBeFetched(@TempDir Path workspace, @TempDir Path server) {
		ClusterLayout layout = new ClusterLayout(workspace, server.resolve("bin"));

		Assertions.assertThat(new EmbeddedDatabaseInstaller(layout, _ -> null).install()).isFalse();
	}

	/**
	 * An archive missing an executable leaves the folder unusable, and reporting
	 * success would send the application on to start a server that is not there.
	 */
	@Test
	void reportsFailureWhenTheArchiveIsIncomplete(@TempDir Path workspace, @TempDir Path server) throws IOException {
		Path archive = archive(server.resolve("postgresql.zip"), "pgsql/bin/postgres.exe");

		ClusterLayout layout = new ClusterLayout(workspace, server.resolve("bin"));

		Assertions.assertThat(installer(layout, archive).install()).isFalse();
	}

	private EmbeddedDatabaseInstaller installer(ClusterLayout layout, Path archive) {
		return new EmbeddedDatabaseInstaller(layout, folder -> {
			try {
				Path copy = folder.resolve("download.zip");

				Files.copy(archive, copy);

				return copy;
			} catch (IOException exception) {
				throw new IllegalStateException(exception);
			}
		});
	}

	private Path archive(Path file, String... entries) throws IOException {
		Files.createDirectories(file.getParent());

		try (OutputStream output = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(output)) {
			for (String entry : entries) {
				zip.putNextEntry(new ZipEntry(entry));
				zip.write(entry.getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}

		return file;
	}
}