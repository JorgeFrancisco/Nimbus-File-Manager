package br.com.jorgemelo.nimbusfilemanager.backup.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.BackupFile;
import br.com.jorgemelo.nimbusfilemanager.backup.infrastructure.persistence.CatalogCopyRepository;

/**
 * What the backup accepts and what it refuses. Restoring is the one action that
 * destroys what it replaces, so every reason to stop before touching a row is
 * worth pinning: a file that is not a backup, one with no manifest, one taken
 * from a schema this build has never seen.
 *
 * <p>
 * A backup from an <em>older</em> schema is deliberately not among the
 * refusals. It is the case the whole format exists for - the dump brings the
 * database back as it was and the migrations carry it forward.
 */
class CatalogBackupServiceTest {

	private static final Clock CLOCK = Clock.fixed(LocalDateTime.parse("2026-08-01T06:00:00").toInstant(ZoneOffset.UTC),
			ZoneOffset.UTC);

	private static final String NAME = "nimbus-catalog-20260731-030000.zip";

	private final CatalogCopyRepository catalogCopyRepository = mock(CatalogCopyRepository.class);
	private final BackupFolderResolver backupFolderResolver = mock(BackupFolderResolver.class);
	private final CatalogDump catalogDump = mock(CatalogDump.class);

	private CatalogBackupService service(Path folder) {
		when(backupFolderResolver.folder()).thenReturn(folder);

		// The injected mapper carries the JSR-310 module; a bare one cannot write the
		// timestamp of the manifest, which is what production actually uses.
		return new CatalogBackupService(catalogCopyRepository, catalogDump, backupFolderResolver,
				new ObjectMapper().findAndRegisterModules(), CLOCK, new BackupProgress());
	}

	private Path backup(Path folder, String manifest) throws IOException {
		Path file = folder.resolve(NAME);

		try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
			if (manifest != null) {
				zip.putNextEntry(new ZipEntry("manifest.json"));
				zip.write(manifest.getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}

			zip.putNextEntry(new ZipEntry("catalog.dump"));
			zip.write("a dump".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}

		return file;
	}

	/**
	 * The reason the file carries the schema and not only the rows: yesterday's
	 * backup has to survive today's migration, or it is not a rescue at all.
	 */
	@Test
	void restoresABackupTakenBeforeTheCurrentSchema(@TempDir Path folder) throws IOException {
		backup(folder, """
				{"schemaVersion":"12","applicationVersion":"5.0.0.1","createdAt":"2026-07-31T03:00:00",
				 "tables":["app_setting"]}
				""");

		when(catalogCopyRepository.schemaVersion()).thenReturn("13");
		when(catalogDump.restore(any())).thenReturn(true);

		Assertions.assertThat(service(folder).restore(NAME).schemaVersion()).isEqualTo("12");

		verify(catalogDump).restore(any());
	}

	/**
	 * Migrations only run forwards. Data written by a later schema names columns
	 * this build has never heard of, and nothing could reconcile them.
	 */
	@Test
	void refusesABackupNewerThanTheRunningSchema(@TempDir Path folder) throws IOException {
		backup(folder, """
				{"schemaVersion":"14","applicationVersion":"5.0.0.1","createdAt":"2026-07-31T03:00:00",
				 "tables":["app_setting"]}
				""");

		when(catalogCopyRepository.schemaVersion()).thenReturn("13");

		CatalogBackupService service = service(folder);

		Assertions.assertThatIllegalArgumentException().isThrownBy(() -> service.restore(NAME))
				.withMessageContaining("schema 14").withMessageContaining("13");

		verify(catalogDump, never()).restore(any());
	}

	/** A zip that is not one of ours: no manifest, nothing to trust. */
	@Test
	void refusesAFileWithoutAManifest(@TempDir Path folder) throws IOException {
		backup(folder, null);

		CatalogBackupService service = service(folder);

		Assertions.assertThatIllegalArgumentException().isThrownBy(() -> service.restore(NAME))
				.withMessageContaining("carries no manifest");

		verify(catalogDump, never()).restore(any());
	}

	@Test
	void refusesANameThatEscapesTheBackupFolder(@TempDir Path folder) {
		CatalogBackupService service = service(folder);

		Assertions.assertThatIllegalArgumentException().isThrownBy(() -> service.delete("../nimbus-catalog-x.zip"));
		Assertions.assertThatIllegalArgumentException().isThrownBy(() -> service.restore("notes.txt"));
	}

	/** A folder with other files in it lists only what this feature wrote. */
	@Test
	void listsOnlyItsOwnBackups(@TempDir Path folder) throws IOException {
		backup(folder, "{}");

		Files.writeString(folder.resolve("holiday.zip"), "not a backup");
		Files.writeString(folder.resolve("notes.txt"), "nor this");

		Assertions.assertThat(service(folder).list()).extracting(BackupFile::name).containsExactly(NAME);
	}

	/**
	 * A folder that cannot be listed - removed drive, revoked permission - costs
	 * the list, never the screen that shows it.
	 */
	@Test
	void answersAnEmptyListWhenTheFolderCannotBeRead(@TempDir Path folder) {
		Assertions.assertThat(service(folder.resolve("gone")).list()).isEmpty();
	}

	@Test
	void namesTheBackupAfterTheMomentItWasTaken(@TempDir Path folder) {
		when(catalogCopyRepository.tables()).thenReturn(List.of());
		when(catalogCopyRepository.schemaVersion()).thenReturn("13");
		when(catalogDump.dump(any())).thenAnswer(call -> {
			Files.writeString(call.getArgument(0), "a dump");

			return true;
		});

		Assertions.assertThat(service(folder).create().name()).isEqualTo("nimbus-catalog-20260801-060000.zip");
	}

	/**
	 * A dump that did not run leaves nothing to wrap, and saying so beats writing
	 * an archive that looks like a backup and holds nothing.
	 */
	@Test
	void reportsADumpThatCouldNotBeTaken(@TempDir Path folder) {
		when(catalogDump.dump(any())).thenReturn(false);

		CatalogBackupService service = service(folder);

		Assertions.assertThatIllegalStateException().isThrownBy(service::create)
				.withMessageContaining("could not be dumped");
	}

	/**
	 * A file that is not a zip at all has to name the file that failed instead of
	 * a stack trace, because the operator is the one who has to act on it.
	 */
	@Test
	void reportsTheFileWhenItIsNotReadableAsABackup(@TempDir Path folder) throws IOException {
		Files.writeString(folder.resolve(NAME), "not a zip at all");

		CatalogBackupService service = service(folder);

		Assertions.assertThatIllegalStateException().isThrownBy(() -> service.restore(NAME))
				.withMessageContaining("Could not read the backup");
	}

	/**
	 * A manifest whose versions are not numbers still has to be ordered somehow.
	 * Falling back to text keeps the guard working instead of letting an
	 * unparseable version through as if it were older.
	 */
	@Test
	void comparesSchemaVersionsThatAreNotNumbers(@TempDir Path folder) throws IOException {
		backup(folder, """
			{"schemaVersion":"2026.08.b","applicationVersion":"5.0.0.1","createdAt":"2026-07-31T03:00:00",
			 "tables":["app_setting"]}
			""");

		when(catalogCopyRepository.schemaVersion()).thenReturn("2026.08.a");

		CatalogBackupService service = service(folder);

		Assertions.assertThatIllegalArgumentException().isThrownBy(() -> service.restore(NAME))
				.withMessageContaining("2026.08.b");
	}

	/** A file that carries a manifest and no dump has nothing to restore from. */
	@Test
	void refusesABackupWithoutADump(@TempDir Path folder) throws IOException {
		Path file = folder.resolve(NAME);

		try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("manifest.json"));
			zip.write("""
				{"schemaVersion":"13","applicationVersion":"5.0.0.1","createdAt":"2026-07-31T03:00:00",
				 "tables":[]}
				""".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}

		when(catalogCopyRepository.schemaVersion()).thenReturn("13");

		CatalogBackupService service = service(folder);

		Assertions.assertThatIllegalArgumentException().isThrownBy(() -> service.restore(NAME))
				.withMessageContaining("carries no dump");
	}

	/**
	 * A folder that vanished under the write has to name the file that failed:
	 * the operator is the one who has to act on it.
	 */
	@Test
	void reportsTheFileWhenTheBackupCannotBeWritten(@TempDir Path folder) {
		when(catalogCopyRepository.tables()).thenReturn(List.of());
		when(catalogDump.dump(any())).thenReturn(true);

		CatalogBackupService service = service(folder.resolve("folder-that-is-not-there"));

		Assertions.assertThatIllegalStateException().isThrownBy(service::create)
				.withMessageContaining("Could not write the backup");
	}

	/** A restore that the tool refused must not report success. */
	@Test
	void reportsARestoreTheToolRefused(@TempDir Path folder) throws IOException {
		backup(folder, """
				{"schemaVersion":"13","applicationVersion":"5.0.0.1","createdAt":"2026-07-31T03:00:00",
				 "tables":["app_setting"]}
				""");

		when(catalogCopyRepository.schemaVersion()).thenReturn("13");
		when(catalogDump.restore(any())).thenReturn(false);

		CatalogBackupService service = service(folder);

		Assertions.assertThatIllegalStateException().isThrownBy(() -> service.restore(NAME))
				.withMessageContaining("could not be loaded");
	}
}