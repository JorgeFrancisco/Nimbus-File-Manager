package br.com.jorgemelo.nimbusfilemanager.shared;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import org.springframework.transaction.support.TransactionTemplate;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Catalogued files for tests, said the way the catalog says them.
 *
 * <p>
 * Where a file is stopped being a column on the file and became a row of its
 * own, so a test that wants "a catalogued photograph at this path" now has two
 * objects to build and to point at each other. Dozens of tests were doing that
 * by hand, and the ones written before the change were doing something else
 * entirely - naming a path in a field the entity no longer has.
 *
 * <p>
 * These build the pair. Nothing here is a shortcut around the model: the
 * location carries the flavor of the path it was given and the canonical
 * spelling is left to the database, exactly as production does it.
 */
public final class CatalogFiles {

	/** A catalogued file living at this path, with everything else left plain. */
	public static CatalogFile at(Path path) {
		return at(null, path);
	}

	public static CatalogFile at(Long id, Path path) {
		return located(CatalogFile.builder().id(id).catalogFilePublicId(UUID.randomUUID()).extension("jpg")
				.sizeBytes(1024L).fileType(FileType.PHOTO).lifecycleStatus(LifecycleStatus.ACTIVE)
				.modifiedAt(Instant.EPOCH).importedAt(Instant.EPOCH).build(), path);
	}

	/**
	 * Puts an already-built file at a path.
	 *
	 * <p>
	 * For the tests that care about the other fields and only need the placement
	 * wired: build the file however the test needs it and hand it here.
	 */
	public static CatalogFile located(CatalogFile file, Path path) {
		Path absolute = path.toAbsolutePath().normalize();

		file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(PathUtils.normalize(absolute))
				.pathFlavor(PathFlavor.of(absolute)).updatedAt(Instant.EPOCH).build());

		return file;
	}

	public static CatalogFile located(CatalogFile file, String path) {
		return located(file, Path.of(path));
	}

	/**
	 * A file catalogued for the first time, written down the way the pass that
	 * catalogues one writes it: the file, and then the placement it was found at.
	 *
	 * <p>
	 * The aggregate does not carry its placement to the database - saving a file is
	 * not how a file moves - so a test that wants a catalogued file on record has
	 * to say both, in the order production says them. This is that, and only that:
	 * there is deliberately no version of it that takes a new path for a file
	 * already on record, because moving one is the doors' to do and leaves a fact
	 * behind.
	 */
	public static CatalogFile catalogued(CatalogFileRepository files, CatalogFileLocationRepository placements,
			Path path) {
		return catalogued(files, placements, at(null, path));
	}

	/**
	 * The same, for a test that runs outside a transaction of its own - one that
	 * commits, because what it proves survives a boundary.
	 *
	 * <p>
	 * The two writes have to share a transaction: a file saved in one of its own is
	 * detached by the time its placement is written, and a placement takes its
	 * identity from the file it places. Production has that transaction already;
	 * these tests have to say it out loud.
	 */
	public static CatalogFile catalogued(TransactionTemplate transaction, CatalogFileRepository files,
			CatalogFileLocationRepository placements, Path path) {
		return transaction.execute(_ -> catalogued(files, placements, path));
	}

	/** The same, for a test that built the file itself. */
	public static CatalogFile catalogued(TransactionTemplate transaction, CatalogFileRepository files,
			CatalogFileLocationRepository placements, CatalogFile file) {
		return transaction.execute(_ -> catalogued(files, placements, file));
	}

	/** The same, for a test that built the file itself. */
	public static CatalogFile catalogued(CatalogFileRepository files, CatalogFileLocationRepository placements,
			CatalogFile file) {
		CatalogFileLocation placement = file.getLocation();

		// The file goes in on its own and the placement follows it, which is the order
		// the catalogue pass uses and the only one that works without an ambient
		// transaction: a save that flushes while the aggregate still names a placement
		// nobody has written is the one thing the mapping no longer does for anybody.
		file.setLocation(null);

		CatalogFile saved = files.save(file);

		placement.setCatalogFile(saved);

		placements.save(placement);

		// Flushed once both are written, never between them: what follows in these
		// tests is usually native SQL, and a row Hibernate is still holding is a row
		// the database has not got.
		placements.flush();

		saved.setLocation(placement);

		return saved;
	}

	private CatalogFiles() {
	}
}