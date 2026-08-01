package br.com.jorgemelo.nimbusfilemanager.backup.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
 * The refusals. Restoring is the one action that destroys what it replaces, so
 * every reason to stop before touching a row is worth pinning: a file that is
 * not a backup, one taken from another schema, one that never had a manifest.
 */
class CatalogBackupServiceTest {

	private static final Clock CLOCK = Clock.fixed(LocalDateTime.parse("2026-08-01T06:00:00").toInstant(ZoneOffset.UTC),
			ZoneOffset.UTC);

	private final CatalogCopyRepository catalogCopyRepository = mock(CatalogCopyRepository.class);
	private final BackupFolderResolver backupFolderResolver = mock(BackupFolderResolver.class);

	private CatalogBackupService service(Path folder) {
		when(backupFolderResolver.folder()).thenReturn(folder);

		// The injected mapper carries the JSR-310 module; a bare one cannot write the
		// timestamp of the manifest, which is what production actually uses.
		return new CatalogBackupService(catalogCopyRepository, backupFolderResolver,
				new ObjectMapper().findAndRegisterModules(), CLOCK);
	}

	private Path backup(Path folder, String manifest) throws IOException {
		Path file = folder.resolve("nimbus-catalog-20260731-030000.zip");

		try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
			if (manifest != null) {
				zip.putNextEntry(new ZipEntry("manifest.json"));
				zip.write(manifest.getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}

			zip.putNextEntry(new ZipEntry("data/app_setting.csv"));
			zip.write("key\n".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}

		return file;
	}

	/**
	 * The guard that matters most: rows written for one schema, loaded into
	 * another, land in columns that moved - which is how a rescue becomes the
	 * corruption it was meant to prevent.
	 */
	@Test
	void refusesABackupTakenFromAnotherSchemaWithoutTouchingTheCatalog(@TempDir Path folder) throws IOException {
		backup(folder, """
				{"schemaVersion":"12","applicationVersion":"5.0.0.1","createdAt":"2026-07-31T03:00:00",
				 "tables":["app_setting"]}
				""");

		when(catalogCopyRepository.schemaVersion()).thenReturn("13");

		CatalogBackupService service = service(folder);

		Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> service.restore("nimbus-catalog-20260731-030000.zip"))
				.withMessageContaining("schema 12").withMessageContaining("13");

		verify(catalogCopyRepository, never()).truncateAll();
		verify(catalogCopyRepository, never()).copyIn(anyString(), any());
	}

	/** A zip that is not one of ours: no manifest, nothing to trust. */
	@Test
	void refusesAFileWithoutAManifest(@TempDir Path folder) throws IOException {
		backup(folder, null);

		CatalogBackupService service = service(folder);

		Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> service.restore("nimbus-catalog-20260731-030000.zip"))
				.withMessageContaining("Not a catalog backup");

		verify(catalogCopyRepository, never()).truncateAll();
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

		List<BackupFile> backups = service(folder).list();

		Assertions.assertThat(backups).extracting(BackupFile::name)
				.containsExactly("nimbus-catalog-20260731-030000.zip");
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

		Assertions.assertThat(service(folder).create().name()).isEqualTo("nimbus-catalog-20260801-060000.zip");
	}

	/**
	 * A file that is not a zip at all, or a folder that vanished under the write:
	 * both have to name the file that failed instead of a stack trace, because the
	 * operator is the one who has to act on it.
	 */
	@Test
	void reportsTheFileWhenItIsNotReadableAsABackup(@TempDir Path folder) throws IOException {
		Files.writeString(folder.resolve("nimbus-catalog-20260731-030000.zip"), "not a zip at all");

		CatalogBackupService service = service(folder);

		Assertions.assertThatIllegalStateException()
				.isThrownBy(() -> service.restore("nimbus-catalog-20260731-030000.zip"))
				.withMessageContaining("Could not read the backup");
	}

	@Test
	void reportsTheFileWhenTheBackupCannotBeWritten(@TempDir Path folder) {
		when(catalogCopyRepository.tables()).thenReturn(List.of());

		CatalogBackupService service = service(folder.resolve("folder-that-is-not-there"));

		Assertions.assertThatIllegalStateException().isThrownBy(service::create)
				.withMessageContaining("Could not write the backup");
	}

	/**
	 * A table listed in the manifest whose data is missing is skipped rather than
	 * failing the restore: the rest of the catalog is worth more than the table
	 * that did not travel.
	 */
	@Test
	void skipsATableTheBackupDoesNotCarry(@TempDir Path folder) throws IOException {
		backup(folder, """
				{"schemaVersion":"13","applicationVersion":"5.0.0.1","createdAt":"2026-07-31T03:00:00",
				 "tables":["app_setting","table_that_is_not_in_the_file"]}
				""");

		when(catalogCopyRepository.schemaVersion()).thenReturn("13");

		service(folder).restore("nimbus-catalog-20260731-030000.zip");

		verify(catalogCopyRepository).copyIn(eq("app_setting"), any());
		verify(catalogCopyRepository, never()).copyIn(eq("table_that_is_not_in_the_file"), any());
	}
}