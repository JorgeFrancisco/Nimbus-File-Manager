package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.application.catalog.CatalogCollectionMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Quarantine is the line every way of forgetting a file stops at.
 *
 * <p>
 * A quarantined file is not deleted - it is held somewhere else, with its way
 * back written down. Everything it needs to come home lives in the catalog:
 * the row, the placement, the movement that says where it came from. Any pass
 * that removes catalog rows in bulk is therefore one wrong predicate away from
 * turning a recoverable file into an orphan on disk that nothing can name.
 *
 * <p>
 * Three passes can remove a catalog row, and each is stopped by a guard of its
 * own - the retention window looks only at MISSING, forgetting a library is
 * scoped by placement and the quarantine folder may not live inside one, and
 * the quarantine purge refuses while a movement still holds the file. The
 * guards are in three different files and none of them knows about the others,
 * so this states the invariant they add up to: it fails if any single one of
 * them is relaxed.
 */
class QuarantineBoundaryIntegrationTest extends SharedPostgresIntegrationTest {

	private static final Path LIBRARY = Path.of("Q:", "library");

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Autowired
	private CatalogCollectionMutations catalogMutations;

	@Autowired
	private QuarantinePurgePersistence purgePersistence;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@PersistenceContext
	private EntityManager entityManager;

	/**
	 * The retention pass ages out files the catalog has lost. A quarantined file
	 * is not lost - it was put somewhere on purpose, and the catalog knows where.
	 */
	@Test
	void theRetentionOfMissingFilesLeavesAQuarantinedOneAlone() {
		CatalogFile quarantined = quarantined();

		catalogMutations.purgeMissingBefore(Instant.now().plusSeconds(3600));

		assertThat(exists(quarantined)).as("held, not lost").isTrue();
		assertThat(quarantineRecordsOf(quarantined)).isOne();
	}

	/**
	 * Forgetting a library drops every file placed under it. The quarantine folder
	 * cannot be under one - the settings screen refuses it in both directions - so
	 * a file already moved there is out of reach of the statement.
	 */
	@Test
	void forgettingTheLibraryLeavesAFileAlreadyInQuarantineAlone() {
		CatalogFile quarantined = quarantined();

		catalogMutations.forgetLibrary(LIBRARY.toString());

		assertThat(exists(quarantined)).as("its placement is in the quarantine folder, not the library").isTrue();
		assertThat(quarantineRecordsOf(quarantined)).isOne();
	}

	/** And the purge refuses while the movement that holds the file is alive. */
	@Test
	void theQuarantinePurgeRefusesWhileTheFileIsStillHeld() {
		CatalogFile quarantined = quarantined();

		assertThat(purgePersistence.deleteCatalogFileIfOrphan(quarantined.getId()))
				.as("the record that says how to bring it back is still there").isFalse();
		assertThat(exists(quarantined)).isTrue();
	}

	/** The same call once nothing holds the file: this is what may proceed. */
	@Test
	void theQuarantinePurgeProceedsOnceNothingHoldsTheFile() {
		CatalogFile quarantined = quarantined();

		jdbcTemplate.update("DELETE FROM movement WHERE catalog_file_id = ?", quarantined.getId());

		assertThat(purgePersistence.deleteCatalogFileIfOrphan(quarantined.getId())).isTrue();
		assertThat(exists(quarantined)).isFalse();
	}

	/**
	 * A file the catalog really has lost, so the retention pass is doing its job -
	 * asserted beside the case above, because a guard that refuses everything
	 * would satisfy that one on its own.
	 */
	@Test
	void theRetentionOfMissingFilesStillRemovesOneNothingHolds() {
		CatalogFile lost = catalogued(LIBRARY.resolve("gone.jpg"));

		jdbcTemplate.update("""
				UPDATE catalog_file SET lifecycle_status = 'MISSING', lifecycle_changed_at = now() - interval '400 days'
				 WHERE id = ?
				""", lost.getId());

		catalogMutations.purgeMissingBefore(Instant.now().minusSeconds(3600));

		assertThat(exists(lost)).isFalse();
	}

	/**
	 * A file held in quarantine: DELETED lifecycle, placed in the quarantine
	 * folder, with its way back on record.
	 */
	private CatalogFile quarantined() {
		CatalogFile file = catalogued(Path.of("Q:", "quarantine", "exec-1", "held.jpg"));

		jdbcTemplate.update("UPDATE catalog_file SET lifecycle_status = 'DELETED', lifecycle_changed_at = ? WHERE id = ?",
				Timestamp.from(Instant.now().minusSeconds(400L * 24 * 3600)), file.getId());

		jdbcTemplate.update("""
				INSERT INTO execution (execution_public_id, execution_type, status, created_at, available_at,
						finished_at)
				VALUES (?, 'EXPLORER_QUARANTINE', 'FINISHED', now(), now(), now())
				""", UUID.randomUUID());

		jdbcTemplate.update("""
				INSERT INTO movement (movement_public_id, execution_id, catalog_file_id, requested_source_path,
						requested_target_path, status, reason, moved_at, catalog_file_event_public_id, prepared_at)
				VALUES (?, (SELECT max(id) FROM execution), ?, ?, ?, 'MOVED', ?, now(), ?, now())
				""", UUID.randomUUID(), file.getId(), LIBRARY.resolve("held.jpg").toString(),
				"Q:\\quarantine\\exec-1\\held.jpg", MovementReason.USER_QUARANTINED.name(), UUID.randomUUID());

		// The rows above went straight to the database, which the session cannot see:
		// what the purge reads has to be the row, not the copy it loaded.
		entityManager.clear();

		return file;
	}

	private CatalogFile catalogued(Path path) {
		return CatalogFiles.catalogued(catalogFileRepository, catalogFileLocationRepository, path);
	}

	private boolean exists(CatalogFile file) {
		return catalogFileRepository.findById(file.getId()).isPresent();
	}

	private int quarantineRecordsOf(CatalogFile file) {
		return jdbcTemplate.queryForObject("""
				SELECT count(*) FROM movement
				 WHERE catalog_file_id = ? AND status = 'MOVED'
				   AND reason IN ('DUPLICATE_QUARANTINED', 'CONVERTED_QUARANTINED', 'USER_QUARANTINED')
				""", Integer.class, file.getId());
	}
}