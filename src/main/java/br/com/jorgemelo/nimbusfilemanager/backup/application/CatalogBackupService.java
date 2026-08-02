package br.com.jorgemelo.nimbusfilemanager.backup.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.BackupFile;
import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.BackupManifest;
import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.CatalogRestored;
import br.com.jorgemelo.nimbusfilemanager.backup.domain.enums.BackupPhase;
import br.com.jorgemelo.nimbusfilemanager.backup.infrastructure.persistence.CatalogSchemaRepository;
import br.com.jorgemelo.nimbusfilemanager.organization.application.SecureFileMove;
import br.com.jorgemelo.nimbusfilemanager.shared.application.BackgroundWorkGate;
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
 * The file is a logical dump wrapped with a manifest that says which schema it
 * came from. It carries the tables and their shape, not only the rows: a backup
 * whose columns were renamed by a later migration still restores, because what
 * comes back is the database as it was, and the migrations bring it forward
 * from there. Rows alone could only ever load into tables shaped exactly as
 * they were on the day.
 */
@Slf4j
@Service
public class CatalogBackupService {

	private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private static final String PREFIX = "nimbus-catalog-";
	private static final String SUFFIX = ".zip";
	private static final String MANIFEST = "manifest.json";
	private static final String DUMP = "catalog.dump";

	private final CatalogSchemaRepository catalogSchemaRepository;
	private final CatalogDump catalogDump;
	private final BackupFolderResolver backupFolderResolver;
	private final ObjectMapper objectMapper;
	private final Clock clock;
	private final BackupProgress progress;
	private final SecureFileMove secureFileMove;
	private final ApplicationEventPublisher eventPublisher;
	private final BackgroundWorkGate backgroundWorkGate;

	public CatalogBackupService(CatalogSchemaRepository catalogSchemaRepository, CatalogDump catalogDump,
			BackupFolderResolver backupFolderResolver, ObjectMapper objectMapper, Clock clock, BackupProgress progress,
			SecureFileMove secureFileMove, ApplicationEventPublisher eventPublisher,
			BackgroundWorkGate backgroundWorkGate) {
		this.catalogSchemaRepository = catalogSchemaRepository;
		this.catalogDump = catalogDump;
		this.backupFolderResolver = backupFolderResolver;
		this.objectMapper = objectMapper;
		this.clock = clock;
		this.progress = progress;
		this.secureFileMove = secureFileMove;
		this.eventPublisher = eventPublisher;
		this.backgroundWorkGate = backgroundWorkGate;
	}

	/**
	 * Writes one file holding the dump and the manifest.
	 *
	 * <p>
	 * Not transactional: the dump opens its own connection and takes its own
	 * consistent snapshot, so a transaction here would be held open for the whole
	 * run while protecting nothing.
	 */
	public BackupFile create() {
		String name = PREFIX + LocalDateTime.now(clock).format(FILE_TIMESTAMP) + SUFFIX;

		Path staging = backupFolderResolver.staging();

		Path dump = staging.resolve(PREFIX + "working.dump");
		// A name of its own, so staging and destination can never be the same file
		// when both resolve to the same folder - the cleanup would delete the backup
		// it had just moved into place.
		Path built = staging.resolve(PREFIX + "building" + SUFFIX);

		Path target = backupFolderResolver.folder().resolve(name);

		progress.start(BackupPhase.EXPORTING, dump);

		try {
			if (!catalogDump.dump(dump)) {
				throw new IllegalStateException("The database could not be dumped: " + catalogDump.lastOutput());
			}

			// Read back before it is kept. A backup is trusted precisely when nobody is
			// in a position to check it, so the check happens now, while the alternative
			// is simply taking it again.
			if (!catalogDump.readable(dump)) {
				throw new IllegalStateException(
					"The dump could not be read back and was discarded: " + catalogDump.lastOutput());
			}

			pack(dump, built);

			// The finished file appears in its folder in one step. Built there instead,
			// it would grow for minutes while the inventory watcher, a cloud client and
			// an antivirus each reacted to every batch of bytes.
			//
			// The secure move rather than a plain one: this is the file a rescue reads,
			// and when the destination is another disk the move is a copy that nothing
			// else would verify. It also announces both paths to the watcher, which is
			// what keeps the arrival from waking an inventory.
			progress.start(BackupPhase.EXPORTING, target);

			secureFileMove.move(built, target, true);
		} catch (IOException exception) {
			deleteQuietly(target);

			throw new IllegalStateException("Could not write the backup " + target, exception);
		} finally {
			deleteQuietly(dump);
			deleteQuietly(built);
		}

		log.info("Catalog backup written to {}", target);

		return describe(target);
	}

	/**
	 * Replaces the whole catalog with the contents of one backup.
	 *
	 * <p>
	 * A backup older than the running schema is accepted: it recreates the
	 * database as it was and Flyway migrates it forward on the next start, which
	 * is the entire reason the file carries the schema and not only the rows. One
	 * <em>newer</em> than the running schema is refused - migrations run forwards
	 * only, so nothing could reconcile columns this build has never seen.
	 */
	public BackupManifest restore(String name) {
		Path file = resolve(name);

		Path dump = backupFolderResolver.folder().resolve(PREFIX + "restoring.dump");

		// Announced before the first table is dropped and cleared only at the end.
		// Every periodic task in this process queries a database that, for the next
		// few minutes, is missing whatever pg_restore is recreating at that instant.
		backgroundWorkGate.restoreStarted();

		try (ZipFile zip = new ZipFile(file.toFile())) {
			BackupManifest manifest = manifest(zip, file);

			refuseWhenNewerThanThisBuild(manifest);

			progress.start(BackupPhase.IMPORTING, dump);

			unpack(zip, dump);

			if (!catalogDump.restore(dump)) {
				throw new IllegalStateException(
					"The backup " + name + " could not be loaded: " + catalogDump.lastOutput());
			}

			// Before anything is told the catalog is back, and while the gate still
			// holds the screens off: whoever reacts to the event queries these tables
			// immediately, and a plan chosen without statistics does not finish.
			catalogSchemaRepository.analyze();

			log.info("Catalog restored from {}", file);

			// Everything this run had read from the database describes an installation
			// that no longer exists. Announced rather than fixed here: who keeps state
			// derived from the catalog is not this domain's business.
			eventPublisher.publishEvent(new CatalogRestored(name));

			return manifest;
		} catch (IOException exception) {
			throw new IllegalStateException("Could not read the backup " + file, exception);
		} finally {
			backgroundWorkGate.restoreFinished();

			deleteQuietly(dump);
		}
	}

	/**
	 * Ends a backup in flight. Nothing to undo afterwards: the half-written file
	 * is deleted by the run that was interrupted, and the database was only ever
	 * read.
	 */
	public boolean cancel() {
		return catalogDump.cancelDump();
	}

	public List<BackupFile> list() {
		Path folder = backupFolderResolver.folder();

		if (!Files.isDirectory(folder)) {
			return List.of();
		}

		try (var files = Files.list(folder)) {
			return files.filter(this::isBackup).map(this::describe)
					.sorted(Comparator.comparing(BackupFile::createdAt).reversed()).toList();
		} catch (IOException exception) {
			log.warn("Could not list the backup folder {}", folder, exception);

			return List.of();
		}
	}

	public void delete(String name) {
		deleteQuietly(resolve(name));
	}

	/**
	 * Only migrations move a database, and they only move it forwards. Data
	 * written by a later schema names columns this build has never heard of.
	 */
	private void refuseWhenNewerThanThisBuild(BackupManifest manifest) {
		String current = catalogSchemaRepository.schemaVersion();

		if (isNewer(manifest.schemaVersion(), current)) {
			throw new IllegalArgumentException("Backup was taken from schema " + manifest.schemaVersion()
					+ ", newer than this installation's " + current);
		}
	}

	/** Schema versions are Flyway's, so they compare as numbers when they are. */
	private boolean isNewer(String candidate, String current) {
		try {
			return Integer.parseInt(candidate.trim()) > Integer.parseInt(current.trim());
		} catch (NumberFormatException exception) {
			log.debug("Comparing schema versions {} and {} as text", candidate, current, exception);

			return candidate.compareTo(current) > 0;
		}
	}

	private void pack(Path dump, Path target) throws IOException {
		try (OutputStream file = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(file)) {
			zip.putNextEntry(new ZipEntry(DUMP));

			Files.copy(dump, zip);

			zip.closeEntry();

			zip.putNextEntry(new ZipEntry(MANIFEST));

			zip.write(objectMapper.writeValueAsBytes(new BackupManifest(catalogSchemaRepository.schemaVersion(),
					applicationVersion(), LocalDateTime.now(clock), catalogSchemaRepository.tables())));

			zip.closeEntry();
		}
	}

	private void unpack(ZipFile zip, Path dump) throws IOException {
		ZipEntry entry = zip.getEntry(DUMP);

		if (entry == null) {
			throw new IllegalArgumentException("The backup carries no dump");
		}

		try (InputStream data = zip.getInputStream(entry)) {
			Files.copy(data, dump, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private BackupManifest manifest(ZipFile zip, Path file) throws IOException {
		ZipEntry entry = zip.getEntry(MANIFEST);

		if (entry == null) {
			throw new IllegalArgumentException("The backup " + file.getFileName() + " carries no manifest");
		}

		try (InputStream data = zip.getInputStream(entry)) {
			return objectMapper.readValue(data, BackupManifest.class);
		}
	}

	/**
	 * Refuses anything that is not a backup of ours inside the backup folder: the
	 * name arrives from a form, and a path traversal here reads and deletes files
	 * the application was never pointed at.
	 */
	private Path resolve(String name) {
		Path folder = backupFolderResolver.folder().toAbsolutePath().normalize();

		Path file = folder.resolve(name).toAbsolutePath().normalize();

		if (!file.startsWith(folder) || !isBackup(file)) {
			throw new IllegalArgumentException("Not a backup of this installation: " + name);
		}

		return file;
	}

	private boolean isBackup(Path file) {
		String name = file.getFileName().toString();

		return Files.isRegularFile(file) && name.startsWith(PREFIX) && name.endsWith(SUFFIX);
	}

	private BackupFile describe(Path file) {
		try {
			return new BackupFile(file.getFileName().toString(), Files.size(file),
					LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault()));
		} catch (IOException exception) {
			throw new IllegalStateException("Could not read the backup " + file, exception);
		}
	}

	private String applicationVersion() {
		Package pack = getClass().getPackage();

		return pack.getImplementationVersion() == null ? "unknown" : pack.getImplementationVersion();
	}

	private void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException exception) {
			log.debug("Could not delete {}", file, exception);
		}
	}
}