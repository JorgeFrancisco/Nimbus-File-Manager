package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * A purge that died halfway, run again.
 *
 * <p>
 * Destroying a quarantined file for good is three writes in three different
 * places - the bytes, the record that says how to bring it back, and the
 * catalogue row - and a process can stop between any two of them. The order is
 * what makes that survivable: the file goes first, so a crash can only ever
 * leave a record for a file that is already gone, never a file nobody has a
 * record of.
 *
 * <p>
 * Each of these leaves the world in the state a crash would have left it in and
 * asks the next pass to finish. What they assert is convergence: the same end
 * state, reached without an error, and without doing anything twice.
 */
class QuarantinePurgeCrashRecoveryIntegrationTest extends SharedPostgresIntegrationTest {

	@Autowired
	private QuarantinePurgePersistence purgePersistence;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@PersistenceContext
	private EntityManager entityManager;

	/**
	 * The bytes went and the process died before the record did. The retry finds
	 * the file already absent, which is the ordinary case rather than an error.
	 */
	@Test
	void aCrashAfterTheBytesLeavesARecordTheNextPassFinishes(@TempDir Path quarantine) throws IOException {
		Path held = Files.writeString(quarantine.resolve("held.jpg"), "bytes");

		CatalogFile file = quarantined(held);

		Files.delete(held);

		assertThat(Files.exists(held)).isFalse();

		UUID movement = movementOf(file);

		assertThat(purgePersistence.deleteMovement(idOf(movement)).removed()).isTrue();

		entityManager.flush();
		entityManager.clear();

		assertThat(purgePersistence.deleteCatalogFileIfOrphan(file.getId())).isTrue();

		entityManager.flush();

		assertThat(catalogFileRepository.findById(file.getId())).isEmpty();
		assertThat(placementsOf(file)).isZero();
	}

	/**
	 * The record went and the process died before the catalogue row did. Nothing
	 * holds the file any more, so the retry may finish - and has to, or the row
	 * stays forever with no way to reach it.
	 */
	@Test
	void aCrashAfterTheRecordLeavesACatalogueRowTheNextPassRemoves(@TempDir Path quarantine) throws IOException {
		CatalogFile file = quarantined(Files.writeString(quarantine.resolve("held.jpg"), "bytes"));

		jdbcTemplate.update("DELETE FROM movement WHERE catalog_file_id = ?", file.getId());

		entityManager.clear();

		assertThat(purgePersistence.deleteCatalogFileIfOrphan(file.getId())).isTrue();
		assertThat(catalogFileRepository.findById(file.getId())).isEmpty();
	}

	/** The file was never there to begin with - the same convergence, no error. */
	@Test
	void aQuarantineWhoseFileIsAlreadyGoneConverges(@TempDir Path quarantine) {
		CatalogFile file = quarantined(quarantine.resolve("never-written.jpg"));

		UUID movement = movementOf(file);

		assertThat(purgePersistence.deleteMovement(idOf(movement)).removed()).isTrue();

		entityManager.flush();
		entityManager.clear();

		assertThat(purgePersistence.deleteCatalogFileIfOrphan(file.getId())).isTrue();
	}

	/** The catalogue row is already gone: nothing to remove and no error. */
	@Test
	void aCatalogueRowThatIsAlreadyGoneIsNotRemovedAgain(@TempDir Path quarantine) throws IOException {
		CatalogFile file = quarantined(Files.writeString(quarantine.resolve("held.jpg"), "bytes"));

		jdbcTemplate.update("DELETE FROM movement WHERE catalog_file_id = ?", file.getId());
		jdbcTemplate.update("DELETE FROM catalog_file WHERE id = ?", file.getId());

		entityManager.clear();

		assertThat(purgePersistence.deleteCatalogFileIfOrphan(file.getId())).as("there is nothing left to purge")
				.isFalse();
		assertThat(catalogFileRepository.findById(file.getId())).isEmpty();
	}

	/**
	 * The whole intention run twice. The second pass finds a world already
	 * converged and changes nothing - which is what tells the caller there was no
	 * eligibility change to announce.
	 */
	@Test
	void runningTheSameIntentionTwiceChangesNothingTheSecondTime(@TempDir Path quarantine) throws IOException {
		CatalogFile file = quarantined(Files.writeString(quarantine.resolve("held.jpg"), "bytes"));

		Long movement = idOf(movementOf(file));

		purgePersistence.deleteMovement(movement);

		entityManager.flush();
		entityManager.clear();

		assertThat(purgePersistence.deleteCatalogFileIfOrphan(file.getId())).isTrue();

		entityManager.flush();
		entityManager.clear();

		assertThat(purgePersistence.deleteMovement(movement).removed()).as("already settled").isFalse();
		assertThat(purgePersistence.deleteCatalogFileIfOrphan(file.getId())).as("nothing new was removed").isFalse();
	}

	private CatalogFile quarantined(Path at) {
		CatalogFile file = CatalogFiles.catalogued(catalogFileRepository, catalogFileLocationRepository, at);

		jdbcTemplate.update("UPDATE catalog_file SET lifecycle_status = 'DELETED' WHERE id = ?", file.getId());

		jdbcTemplate.update("""
				INSERT INTO execution (execution_public_id, execution_type, status, created_at, available_at,
						finished_at)
				VALUES (?, 'EXPLORER_QUARANTINE', 'FINISHED', now(), now(), now())
				""", UUID.randomUUID());

		jdbcTemplate.update("""
				INSERT INTO movement (movement_public_id, execution_id, catalog_file_id, requested_source_path,
						requested_target_path, status, reason, moved_at, catalog_file_event_public_id, prepared_at)
				VALUES (?, (SELECT max(id) FROM execution), ?, ?, ?, 'MOVED', ?, now(), ?, now())
				""", UUID.randomUUID(), file.getId(), "D:\\library\\held.jpg", at.toString(),
				MovementReason.USER_QUARANTINED.name(), UUID.randomUUID());

		entityManager.clear();

		return file;
	}

	private UUID movementOf(CatalogFile file) {
		return jdbcTemplate.queryForObject("SELECT movement_public_id FROM movement WHERE catalog_file_id = ?",
				UUID.class, file.getId());
	}

	private Long idOf(UUID movementPublicId) {
		return jdbcTemplate.queryForObject("SELECT id FROM movement WHERE movement_public_id = ?", Long.class,
				movementPublicId);
	}

	private int placementsOf(CatalogFile file) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM catalog_file_location WHERE catalog_file_id = ?",
				Integer.class, file.getId());
	}
}