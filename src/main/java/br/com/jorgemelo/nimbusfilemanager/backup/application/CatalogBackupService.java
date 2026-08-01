package br.com.jorgemelo.nimbusfilemanager.backup.application;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.BackupFile;
import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.BackupManifest;
import br.com.jorgemelo.nimbusfilemanager.backup.infrastructure.persistence.CatalogCopyRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Copies the catalog out to a file, and back in from one.
 *
 * <p>
 * The catalog is the work the application accumulates and the files on disk
 * cannot rebuild: extracted metadata, perceptual hashes that cost hours of
 * ffmpeg, resolved locations, the movement history that makes an organization
 * undoable, and the duplicate decisions taken by hand. Losing the database with
 * every file intact still means starting that over - which is the difference
 * between reinstalling and continuing, and reinstalling and beginning again.
 *
 * <p>
 * A restore replaces everything. It refuses a backup taken from a different
 * schema version, because loading rows into columns that moved is how a rescue
 * becomes the corruption it was meant to prevent.
 */
@Slf4j @Service
public class CatalogBackupService {

	private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final String PREFIX = "nimbus-catalog-";
	private static final String SUFFIX = ".zip";
	private static final String MANIFEST = "manifest.json";
	private static final String DATA = "data/";

	private final CatalogCopyRepository catalogCopyRepository;
	private final BackupFolderResolver backupFolderResolver;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public CatalogBackupService(CatalogCopyRepository catalogCopyRepository, BackupFolderResolver backupFolderResolver,
			ObjectMapper objectMapper, Clock clock) {
		this.catalogCopyRepository = catalogCopyRepository;
		this.backupFolderResolver = backupFolderResolver;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	/**
	 * Writes one file with every table as CSV plus the manifest. Read-only against
	 * the catalog, so it is safe to run while the application is being used - the
	 * result is a snapshot of the moment each table was read.
	 */
	@Transactional(readOnly = true)
	public BackupFile create() {
		List<String> tables = catalogCopyRepository.tables();

		Path target = backupFolderResolver.folder()
				.resolve(PREFIX + LocalDateTime.now(clock).format(FILE_TIMESTAMP) + SUFFIX);

		try (OutputStream file = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(file)) {
			for (String table : tables) {
				zip.putNextEntry(new ZipEntry(DATA + table + ".csv"));

				catalogCopyRepository.copyOut(table, zip);

				zip.closeEntry();
			}

			zip.putNextEntry(new ZipEntry(MANIFEST));

			zip.write(objectMapper.writeValueAsBytes(new BackupManifest(catalogCopyRepository.schemaVersion(),
					applicationVersion(), LocalDateTime.now(clock), tables)));

			zip.closeEntry();
		} catch (IOException e) {
			deleteQuietly(target);

			throw new IllegalStateException("Could not write the backup " + target, e);
		}

		log.info("Catalog backup written to {}", target);

		return describe(target);
	}

	/** The backups on disk, newest first. */
	public List<BackupFile> list() {
		Path folder = backupFolderResolver.folder();

		try (var files = Files.list(folder)) {
			return files.filter(this::isBackup).map(this::describe)
					.sorted(Comparator.comparing(BackupFile::createdAt).reversed()).toList();
		} catch (IOException e) {
			log.warn("Could not list the backup folder {}", folder, e);

			return List.of();
		}
	}

	/**
	 * Replaces the whole catalog with the contents of one backup. Everything runs
	 * in a single transaction: a restore that failed halfway would leave neither
	 * the old catalog nor the new one.
	 */
	@Transactional
	public BackupManifest restore(String name) {
		Path file = resolve(name);

		try (ZipFile zip = new ZipFile(file.toFile())) {
			BackupManifest manifest = manifest(zip, file);

			String current = catalogCopyRepository.schemaVersion();

			if (!current.equals(manifest.schemaVersion())) {
				throw new IllegalArgumentException("Backup was taken from schema " + manifest.schemaVersion()
						+ ", this database is on " + current);
			}

			catalogCopyRepository.truncateAll();

			for (String table : manifest.tables()) {
				load(zip, table);
			}

			catalogCopyRepository.realignSequences();

			log.info("Catalog restored from {}", file);

			return manifest;
		} catch (IOException e) {
			throw new IllegalStateException("Could not read the backup " + file, e);
		}
	}

	public void delete(String name) {
		deleteQuietly(resolve(name));
	}

	private void load(ZipFile zip, String table) throws IOException {
		ZipEntry entry = zip.getEntry(DATA + table + ".csv");

		if (entry == null) {
			return;
		}

		try (var data = new BufferedInputStream(zip.getInputStream(entry))) {
			catalogCopyRepository.copyIn(table, data);
		}
	}

	private BackupManifest manifest(ZipFile zip, Path file) throws IOException {
		ZipEntry entry = zip.getEntry(MANIFEST);

		if (entry == null) {
			throw new IllegalArgumentException("Not a catalog backup: " + file.getFileName());
		}

		try (var data = zip.getInputStream(entry)) {
			return objectMapper.readValue(data, BackupManifest.class);
		}
	}

	/**
	 * Only a file name, never a path: the screen sends what it listed, and a name
	 * carrying separators would reach outside the backup folder.
	 */
	private Path resolve(String name) {
		Path folder = backupFolderResolver.folder();

		Path file = folder.resolve(name).normalize();

		if (!file.getParent().equals(folder.normalize()) || !isBackup(file)) {
			throw new IllegalArgumentException("Not a backup of this installation: " + name);
		}

		return file;
	}

	private boolean isBackup(Path file) {
		String name = file.getFileName().toString();

		return name.startsWith(PREFIX) && name.endsWith(SUFFIX);
	}

	private BackupFile describe(Path file) {
		try {
			return new BackupFile(file.getFileName().toString(), Files.size(file),
					LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), zone()));
		} catch (IOException e) {
			throw new IllegalStateException("Could not read the backup " + file, e);
		}
	}

	private ZoneId zone() {
		return clock.getZone();
	}

	private String applicationVersion() {
		Package pack = getClass().getPackage();

		return pack.getImplementationVersion() == null ? "unknown" : pack.getImplementationVersion();
	}

	private void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			log.warn("Could not delete {}", file, e);
		}
	}
}