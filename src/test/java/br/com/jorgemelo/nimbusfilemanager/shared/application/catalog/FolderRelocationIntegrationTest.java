package br.com.jorgemelo.nimbusfilemanager.shared.application.catalog;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Savepoint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventEvidence;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.application.LocationChangeException;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationChangeFailure;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Renaming a folder moves everything under it in one operating-system call, and
 * this is the catalog keeping up with it.
 *
 * <p>
 * Against a real Postgres because that is where it happens: the folder is
 * matched by canonical key rather than by {@code LIKE}, precisely so a path full
 * of separators - the escape character, on Windows - and file names full of
 * {@code _} and {@code %} - the wildcards - cannot change what it matches. A
 * test over mocks would prove none of that.
 *
 * <p>
 * The folders are real and absolute, under a temporary root, because the
 * operation normalizes what it is given and reads the spelling rules from the
 * running file system. Windows-shaped literals therefore described nothing on
 * Linux - a single relative segment, prefixed with the working directory.
 * Nothing is written to disk even so; the root is borrowed only for its shape.
 *
 * <p>
 * What the names carry is the whole point and survives every rewrite of this
 * class: a {@code %} and an {@code _} that must not act as wildcards, and a
 * sibling folder whose name merely begins the same.
 */
class FolderRelocationIntegrationTest extends SharedPostgresIntegrationTest {

	private static final String SOURCE = "TEST";

	/** What stands behind these facts: an operation of ours moved the folder. */
	private static final String EVIDENCE = CatalogEventEvidence.NIMBUS_OPERATION;

	@TempDir
	Path library;

	@Autowired
	private CatalogConvergenceMutations catalogMutations;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private String oldFolder;
	private String newFolder;
	private String siblingFolder;

	@BeforeEach
	void nameTheFolders() {
		oldFolder = at("fotos", "album_2008");
		newFolder = at("fotos", "viagem 100%");
		siblingFolder = at("fotos", "album_2009");
	}

	@Test
	void movesEveryCataloguedFileUnderTheFolderAndLeavesTheNeighboursAlone() {
		long direct = catalogued(under(oldFolder, "a_1.jpg"));
		long deep = catalogued(under(oldFolder, "2008", "praia", "b%2.jpg"));
		long sibling = catalogued(under(siblingFolder, "c.jpg"));

		int relocated = catalogMutations.repointFolder(oldFolder, newFolder, Instant.now(), SOURCE, EVIDENCE);

		Assertions.assertThat(relocated).isEqualTo(2);
		Assertions.assertThat(currentPathOf(direct)).isEqualTo(under(newFolder, "a_1.jpg"));
		Assertions.assertThat(currentPathOf(deep)).isEqualTo(under(newFolder, "2008", "praia", "b%2.jpg"));
		Assertions.assertThat(currentPathOf(sibling))
				.as("a folder whose name merely starts the same is not under it")
				.isEqualTo(under(siblingFolder, "c.jpg"));
	}

	/** The columns the database derives follow, because it derives them again. */
	@Test
	void theFolderTheCatalogRecordsFollowsThePath() {
		long direct = catalogued(under(oldFolder, "a.jpg"));
		long deep = catalogued(under(oldFolder, "2008", "b.jpg"));

		catalogMutations.repointFolder(oldFolder, newFolder, Instant.now(), SOURCE, EVIDENCE);

		Assertions.assertThat(currentFolderOf(direct)).isEqualTo(newFolder);
		Assertions.assertThat(currentFolderOf(deep)).isEqualTo(under(newFolder, "2008"));
	}

	/**
	 * One fact per file and none for the folder. A folder has no row and no
	 * identity; what a screen or a later reconciliation has to be able to read is
	 * where each file went, which is a question about files.
	 */
	@Test
	void recordsOneFactPerFileSayingWhereItCameFrom() {
		long direct = catalogued(under(oldFolder, "a.jpg"));
		long deep = catalogued(under(oldFolder, "2008", "b.jpg"));

		catalogMutations.repointFolder(oldFolder, newFolder, Instant.now(), SOURCE, EVIDENCE);

		Assertions.assertThat(factsFor(direct)).isEqualTo(1);
		Assertions.assertThat(factsFor(deep)).isEqualTo(1);
		Assertions.assertThat(oldPathOf(direct)).isEqualTo(under(oldFolder, "a.jpg"));
		Assertions.assertThat(newPathOf(direct)).isEqualTo(under(newFolder, "a.jpg"));
	}

	/**
	 * What the user did was rename a folder; what happened to a file inside it is
	 * that its folder changed, which is a move. The two are different statements
	 * and only the second is a fact about a file.
	 */
	@Test
	void aFolderRenameIsAMoveForEveryFileInIt() {
		long direct = catalogued(under(oldFolder, "a.jpg"));

		catalogMutations.repointFolder(oldFolder, newFolder, Instant.now(), SOURCE, EVIDENCE);

		Assertions.assertThat(eventTypeOf(direct)).isEqualTo("MOVED");
	}

	/**
	 * Nine hundred moved and one refused is a folder in two places. The whole
	 * batch is checked before a single row is written, so the refusal leaves the
	 * catalog exactly as it found it.
	 */
	@Test
	void oneOccupiedDestinationStopsTheWholeFolder() {
		long direct = catalogued(under(oldFolder, "a.jpg"));
		long deep = catalogued(under(oldFolder, "2008", "b.jpg"));

		catalogued(under(newFolder, "a.jpg"));

		// PostgreSQL aborts a transaction on error and refuses every command after it,
		// so the refusal is taken behind a savepoint: what follows is the check that
		// nothing moved, and it has to be able to run.
		jdbcTemplate.execute((Connection connection) -> {
			Savepoint beforeTheRefusal = connection.setSavepoint("refusal");

			Assertions
					.assertThatThrownBy(
							() -> catalogMutations.repointFolder(oldFolder, newFolder, Instant.now(), SOURCE, EVIDENCE))
					.isInstanceOfSatisfying(LocationChangeException.class, refusal -> Assertions
							.assertThat(refusal.getFailure()).isEqualTo(LocationChangeFailure.PATH_OCCUPIED));

			connection.rollback(beforeTheRefusal);

			return null;
		});

		Assertions.assertThat(currentPathOf(direct)).isEqualTo(under(oldFolder, "a.jpg"));
		Assertions.assertThat(currentPathOf(deep)).isEqualTo(under(oldFolder, "2008", "b.jpg"));
		Assertions.assertThat(factsFor(direct)).isZero();
	}

	/** A folder with nothing catalogued under it writes nothing. */
	@Test
	void aFolderWithNothingCataloguedUnderItWritesNothing() {
		Assertions.assertThat(catalogMutations.repointFolder(oldFolder, newFolder, Instant.now(), SOURCE, EVIDENCE)).isZero();
	}

	private String at(String... names) {
		return PathUtils.normalize(Path.of(library.toString(), names));
	}

	private String under(String folder, String... names) {
		return PathUtils.normalize(Path.of(folder, names));
	}

	private long catalogued(String path) {
		long file = jdbcTemplate.queryForObject("""
				INSERT INTO catalog_file (catalog_file_public_id, extension, size_bytes, modified_at, file_type,
						lifecycle_status)
				VALUES (?, 'jpg', 1024, now(), 'PHOTO', 'ACTIVE')
				RETURNING id
				""", Long.class, UUID.randomUUID());

		jdbcTemplate.update("""
				INSERT INTO catalog_file_location (catalog_file_id, current_path, path_flavor)
				VALUES (?, ?, ?)
				""", file, path, PathFlavor.of(Path.of(path)).name());

		return file;
	}

	private String currentPathOf(long catalogFileId) {
		return text("SELECT current_path FROM catalog_file_location WHERE catalog_file_id = ?", catalogFileId);
	}

	private String currentFolderOf(long catalogFileId) {
		return text("SELECT current_folder FROM catalog_file_location WHERE catalog_file_id = ?", catalogFileId);
	}

	private String eventTypeOf(long catalogFileId) {
		return text("SELECT event_type FROM catalog_file_event WHERE catalog_file_id = ?", catalogFileId);
	}

	private String oldPathOf(long catalogFileId) {
		return text("SELECT old_path FROM catalog_file_event WHERE catalog_file_id = ?", catalogFileId);
	}

	private String newPathOf(long catalogFileId) {
		return text("SELECT new_path FROM catalog_file_event WHERE catalog_file_id = ?", catalogFileId);
	}

	private long factsFor(long catalogFileId) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM catalog_file_event WHERE catalog_file_id = ?",
				Long.class, catalogFileId);
	}

	/**
	 * Read in the transaction the relocation wrote in - a connection of its own
	 * would be a second transaction, and this class is {@code @Transactional}, so
	 * it would find none of it.
	 *
	 * @return null where there is no row, which is what the empty cases assert
	 */
	private String text(String sql, long catalogFileId) {
		List<String> found = jdbcTemplate.queryForList(sql, String.class, catalogFileId);

		return found.isEmpty() ? null : found.getFirst();
	}
}