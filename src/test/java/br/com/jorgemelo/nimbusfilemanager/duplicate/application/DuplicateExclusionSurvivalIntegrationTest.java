package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.ContentReconciliation;
import br.com.jorgemelo.nimbusfilemanager.catalog.domain.enums.ContentOutcome;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.application.catalog.CatalogCollectionMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventEvidence;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.CatalogFactProvenance;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.LocationChange;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.CatalogLocationWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;
import jakarta.persistence.EntityManager;

/**
 * What takes a judgement away with the file, and what leaves it standing.
 *
 * <p>
 * The exclusion belongs to a catalogued file and to nothing else, which is a
 * claim about the database rather than about any Java: no service deletes these
 * rows when a file goes, and the reason nothing is left behind is a foreign key
 * with {@code ON DELETE CASCADE}. A test with a mocked repository cannot see
 * that at all - it would pass just as well if the cascade had been dropped in a
 * migration.
 *
 * <p>
 * The other half is the one worth more: quarantine holds a file rather than
 * losing it, so nothing about the judgement may change on the way out or on the
 * way back. A cascade that fired there would silently forget a preference the
 * user still holds, and the only symptom would be a photo reappearing in
 * Duplicados months later.
 */
class DuplicateExclusionSurvivalIntegrationTest extends SharedPostgresIntegrationTest {

	private static final String BYTES = "c".repeat(64);
	private static final Instant SEEN_AT = Instant.parse("2026-08-15T10:00:00Z");

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Autowired
	private CatalogCollectionMutations catalogMutations;

	@Autowired
	private CatalogLocationWriter catalogLocationWriter;

	@Autowired
	private ContentReconciliation contentReconciliation;

	@Autowired
	private DuplicateExclusionService duplicateExclusionService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	/**
	 * Forgetting a library is a hard purge of what is in it, and a judgement about
	 * a file that no longer exists is not a preference to keep - there is nothing
	 * left for it to be about.
	 */
	@Test
	void forgettingALibraryTakesTheJudgementsAboutItsFilesAndLeavesTheOthers(@TempDir Path root) {
		Path forgotten = root.resolve("A");
		Path kept = root.resolve("B");

		CatalogFile inside = catalogued(forgotten.resolve("photo.jpg"));
		CatalogFile deeper = catalogued(forgotten.resolve("2026").resolve("nested.jpg"));
		CatalogFile elsewhere = catalogued(kept.resolve("photo.jpg"));

		exclude(inside);
		exclude(deeper);
		exclude(elsewhere);

		entityManager.flush();
		entityManager.clear();

		Assertions.assertThat(catalogMutations.forgetLibrary(forgotten.toString())).isEqualTo(2);

		Assertions.assertThat(judgementsAbout(inside)).as("its file is gone, and so is what was said about it")
				.isZero();
		Assertions.assertThat(judgementsAbout(deeper)).isZero();
		Assertions.assertThat(judgementsAbout(elsewhere)).as("a file in the library that stays keeps everything")
				.isOne();

		Assertions.assertThat(orphanedJudgements()).as("nothing is left pointing at a file that is not there")
				.isZero();
	}

	/**
	 * Nobody deletes these rows by hand on this path. Proved by the count: the
	 * statement that forgets a library selects {@code catalog_file} and never names
	 * the exclusion table, so if the cascade were gone this would be the test that
	 * says so rather than a stray orphan found years later.
	 */
	@Test
	void whatRemovesTheJudgementIsTheForeignKeyAndNotAService(@TempDir Path root) {
		CatalogFile lost = catalogued(root.resolve("gone.jpg"));

		exclude(lost);

		entityManager.flush();
		entityManager.clear();

		jdbcTemplate.update("""
				UPDATE catalog_file SET lifecycle_status = 'MISSING', lifecycle_changed_at = now() - interval '400 days'
				 WHERE id = ?
				""", lost.getId());

		Assertions.assertThat(catalogMutations.purgeMissingBefore(SEEN_AT)).isOne();

		Assertions.assertThat(judgementsAbout(lost)).isZero();
	}

	/**
	 * A quarantined file is held, not lost: the catalog entry stays, the placement
	 * moves to the quarantine folder and the lifecycle says removed. None of the
	 * three is anything the judgement was about, so it goes on applying - and the
	 * user who lifts it out of quarantine finds their preference where they left
	 * it.
	 */
	@Test
	void holdingAFileInQuarantineChangesNothingAboutTheJudgement(@TempDir Path root) {
		Path library = root.resolve("library");
		Path quarantine = root.resolve("quarantine");

		CatalogFile photo = catalogued(library.resolve("photo.jpg"));

		exclude(photo);

		quarantine(photo, library.resolve("photo.jpg"), quarantine.resolve("photo.jpg"));

		Assertions.assertThat(catalogFileRepository.findById(photo.getId())).as("held somewhere else, not deleted")
				.isPresent();

		Assertions.assertThat(judgementsAbout(photo)).as("no cascade fires for a file that is still catalogued")
				.isOne();
		Assertions.assertThat(revisionsJudged(photo)).containsExactly(currentRevision(photo));

		Assertions.assertThat(duplicateExclusionService.excludedFilePublicIds())
				.as("the decision still bears on the bytes the file is holding")
				.contains(photo.getCatalogFilePublicId());
	}

	/**
	 * And back. A restore proves the digest on the way - the move reads the file
	 * twice to verify itself - so the catalog is told what it found, and the point
	 * is that being told the bytes it already holds moves no generation. Were it
	 * to, restoring a file would quietly cancel a preference nobody withdrew.
	 */
	@Test
	void bringingTheSameBytesBackLeavesTheJudgementApplying(@TempDir Path root) {
		Path library = root.resolve("library");
		Path quarantine = root.resolve("quarantine");

		CatalogFile photo = catalogued(library.resolve("photo.jpg"));

		exclude(photo);

		long judged = currentRevision(photo);

		quarantine(photo, library.resolve("photo.jpg"), quarantine.resolve("photo.jpg"));

		restore(photo, quarantine.resolve("photo.jpg"), library.resolve("photo.jpg"));

		Assertions.assertThat(currentRevision(photo)).as("the same bytes came back, so nothing changed about them")
				.isEqualTo(judged);

		Assertions.assertThat(revisionsJudged(photo)).containsExactly(judged);

		Assertions.assertThat(duplicateExclusionService.excludedFilePublicIds())
				.contains(photo.getCatalogFilePublicId());
	}

	/**
	 * The half of quarantine that does end the judgement: erasing the file for
	 * good. Nothing about the exclusion is mentioned by the purge - the row goes
	 * because the file it belonged to went.
	 */
	@Test
	void erasingAQuarantinedFileForGoodTakesTheJudgementWithIt(@TempDir Path root) {
		CatalogFile photo = catalogued(root.resolve("photo.jpg"));

		exclude(photo);

		entityManager.flush();
		entityManager.clear();

		jdbcTemplate.update("DELETE FROM catalog_file WHERE id = ?", photo.getId());

		Assertions.assertThat(judgementsAbout(photo)).isZero();
		Assertions.assertThat(orphanedJudgements()).isZero();
	}

	/**
	 * What quarantine intake does to the catalog: the placement moves and the
	 * entry is counted as removed. The movement and the bytes on disk belong to
	 * the operation rather than to what is being proved here.
	 */
	private void quarantine(CatalogFile photo, Path from, Path to) {
		CatalogFile held = reload(photo);

		relocate(held, from, to);

		held.markDeleted();

		catalogFileRepository.save(held);

		learn(held);
	}

	/** And the way back, which is the same three statements reversed. */
	private void restore(CatalogFile photo, Path from, Path to) {
		CatalogFile back = reload(photo);

		relocate(back, from, to);

		back.markActive();

		catalogFileRepository.save(back);

		learn(back);
	}

	/**
	 * The digest the secure move proved, handed to the catalog exactly as the
	 * quarantine persistence hands it over.
	 */
	private void learn(CatalogFile file) {
		Assertions
				.assertThat(contentReconciliation.reconcileFromDigest(file, BYTES, file.getSizeBytes(),
						CatalogEventSources.QUARANTINE, SEEN_AT))
				.as("the bytes are the ones on record, and saying so is not a change")
				.isEqualTo(ContentOutcome.ALREADY_CONVERGED);
	}

	private void relocate(CatalogFile file, Path from, Path to) {
		catalogLocationWriter.relocate(new LocationChange(file.getId(), UuidV7.generate(), from, to,
				new CatalogFactProvenance(SEEN_AT, CatalogEventSources.QUARANTINE,
						CatalogEventEvidence.NIMBUS_OPERATION, null)));
	}

	/**
	 * Written down and forgotten, so what follows reads the row rather than the
	 * copy the session is holding - the location door writes the tables directly.
	 */
	private CatalogFile reload(CatalogFile file) {
		entityManager.flush();
		entityManager.clear();

		return catalogFileRepository.findById(file.getId()).orElseThrow();
	}

	private void exclude(CatalogFile file) {
		Assertions.assertThat(duplicateExclusionService.excludeFile(file.getCatalogFilePublicId())).isTrue();
	}

	private CatalogFile catalogued(Path path) {
		CatalogFile file = CatalogFile.builder().catalogFilePublicId(UUID.randomUUID()).extension("jpg")
				.sizeBytes(1024L).fileType(FileType.PHOTO).lifecycleStatus(LifecycleStatus.ACTIVE).sha256(BYTES)
				.modifiedAt(Instant.EPOCH).importedAt(Instant.EPOCH).build();

		return CatalogFiles.catalogued(catalogFileRepository, catalogFileLocationRepository,
				CatalogFiles.located(file, path));
	}

	private long currentRevision(CatalogFile file) {
		entityManager.flush();

		return jdbcTemplate.queryForObject("SELECT content_revision FROM catalog_file WHERE id = ?", Long.class,
				file.getId());
	}

	private List<Long> revisionsJudged(CatalogFile file) {
		entityManager.flush();

		return jdbcTemplate.queryForList("SELECT content_revision FROM duplicate_exclusion_file WHERE "
				+ "catalog_file_id = ?", Long.class, file.getId());
	}

	private int judgementsAbout(CatalogFile file) {
		entityManager.flush();

		return jdbcTemplate.queryForObject("SELECT count(*) FROM duplicate_exclusion_file WHERE catalog_file_id = ?",
				Integer.class, file.getId());
	}

	private int orphanedJudgements() {
		return jdbcTemplate.queryForObject("""
				SELECT count(*) FROM duplicate_exclusion_file e
				 WHERE NOT EXISTS (SELECT 1 FROM catalog_file m WHERE m.id = e.catalog_file_id)
				""", Integer.class);
	}
}