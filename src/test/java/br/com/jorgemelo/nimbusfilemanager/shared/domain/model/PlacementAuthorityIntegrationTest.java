package br.com.jorgemelo.nimbusfilemanager.shared.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import jakarta.persistence.EntityManager;

/**
 * Who is allowed to move a file, as far as the database is concerned.
 *
 * <p>
 * Placement is written by one door, and every operation that moves a file goes
 * through it. The aggregate is the other way in: a {@code CatalogFile} read
 * before that door ran still carries the old placement, and saving it used to
 * put the old path back - the catalog silently returning to where the file no
 * longer was. Phase 14 fixed the five callers that hit it; this is about the
 * mapping that allowed it, so that the sixth caller cannot.
 *
 * <p>
 * The aggregate keeps what it genuinely owns: a file catalogued for the first
 * time is saved with its placement in one go, and deleting the file takes the
 * placement with it. What it no longer has is the power to rewrite a placement
 * it did not observe.
 */
class PlacementAuthorityIntegrationTest extends SharedPostgresIntegrationTest {

	private static final Path CATALOGUED = Path.of("D:", "library", "before.jpg");

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	/**
	 * The regression itself. The door moves the file while a copy of the aggregate
	 * is held elsewhere - a plan built before the move, a retry holding what it
	 * read - and saving that copy must not undo the move.
	 */
	@Test
	void savingAnAggregateReadBeforeTheMoveDoesNotUndoIt() {
		CatalogFile stale = catalogued();

		assertThat(stale.getLocation().getCurrentPath()).as("what the copy believes")
				.isEqualTo("D:\\library\\before.jpg");

		// The whole session is gone, which is the shape the defect needed: a plan
		// built before the move, or a retry carrying what it read. Inside a session,
		// the snapshot Hibernate compares against is as old as the copy, so it writes
		// nothing and the door's row was never in danger.
		entityManager.clear();

		jdbcTemplate.update("UPDATE catalog_file_location SET current_path = ? WHERE catalog_file_id = ?",
				"D:\\library\\organised\\after.jpg", stale.getId());

		stale.setSizeBytes(2048L);

		catalogFileRepository.saveAndFlush(stale);

		entityManager.clear();

		assertThat(pathOf(stale.getId())).as("the placement the door wrote")
				.isEqualTo("D:\\library\\organised\\after.jpg");
	}

	/** What the aggregate owns: its columns save, the placement is untouched. */
	@Test
	void theRestOfTheAggregateStillSavesWithoutTouchingThePlacement() {
		CatalogFile file = catalogued();

		file.setSizeBytes(4096L);

		catalogFileRepository.saveAndFlush(file);

		entityManager.clear();

		assertThat(catalogFileRepository.findById(file.getId()).orElseThrow().getSizeBytes()).isEqualTo(4096L);
		assertThat(pathOf(file.getId())).isEqualTo("D:\\library\\before.jpg");
	}

	/** A file catalogued for the first time is saved with its placement. */
	@Test
	void aFileCataloguedForTheFirstTimeIsSavedWithItsPlacement() {
		CatalogFile file = catalogued();

		assertThat(pathOf(file.getId())).isEqualTo("D:\\library\\before.jpg");
	}

	/** The placement is a component: the file leaving takes it along. */
	@Test
	void deletingTheFileTakesThePlacementWithIt() {
		CatalogFile file = catalogued();

		catalogFileRepository.delete(file);
		catalogFileRepository.flush();

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM catalog_file_location WHERE catalog_file_id = ?", Integer.class, file.getId()))
				.isZero();
	}

	private CatalogFile catalogued() {
		CatalogFile file = CatalogFiles.catalogued(catalogFileRepository, catalogFileLocationRepository, CATALOGUED);

		entityManager.flush();

		return file;
	}

	private String pathOf(Long catalogFileId) {
		return jdbcTemplate.queryForObject("SELECT current_path FROM catalog_file_location WHERE catalog_file_id = ?",
				String.class, catalogFileId);
	}
}