package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.ContentReconciliation;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentObservation;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentState;
import br.com.jorgemelo.nimbusfilemanager.catalog.domain.enums.ContentOutcome;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import jakarta.persistence.EntityManager;

/**
 * A decision about bytes does not protect other bytes.
 *
 * <p>
 * "Never offer me this one as a duplicate" is said while looking at a
 * photograph. Replace what is behind that path and the sentence is about
 * something nobody has seen - so the row stays, because the user did say it, and
 * stops applying, because what it was about is gone.
 *
 * <p>
 * Only a real database can show this. The revision moves inside
 * {@code apply_content_change}, the comparison that makes the exclusion apply is
 * a JPQL predicate over two tables, and a service test with a mocked repository
 * would agree with itself whichever way the predicate was written.
 */
class DuplicateExclusionContentRevisionIntegrationTest extends SharedPostgresIntegrationTest {

	private static final String JUDGED = "a".repeat(64);
	private static final String REPLACED = "b".repeat(64);
	private static final Instant SEEN_AT = Instant.parse("2026-08-15T10:00:00Z");

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Autowired
	private DuplicateExclusionService duplicateExclusionService;

	@Autowired
	private ContentReconciliation contentReconciliation;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	private CatalogFile photo;

	@BeforeEach
	void catalogued(@TempDir Path library) {
		CatalogFile file = CatalogFile.builder().catalogFilePublicId(UUID.randomUUID()).extension("jpg")
				.sizeBytes(1024L).fileType(FileType.PHOTO).lifecycleStatus(LifecycleStatus.ACTIVE).sha256(JUDGED)
				.modifiedAt(Instant.EPOCH).importedAt(Instant.EPOCH).build();

		photo = CatalogFiles.catalogued(catalogFileRepository, catalogFileLocationRepository,
				CatalogFiles.located(file, library.resolve("photo.jpg")));
	}

	@Test
	void aJudgementAboutTheBytesOnRecordHidesTheFile() {
		Assertions.assertThat(duplicateExclusionService.excludeFile(photo.getCatalogFilePublicId())).isTrue();

		Assertions.assertThat(duplicateExclusionService.excludedFilePublicIds())
				.containsExactly(photo.getCatalogFilePublicId());
	}

	/**
	 * The point of the revision, in one test: the row survives and the hiding does
	 * not.
	 */
	@Test
	void replacingTheBytesKeepsTheRowAndStopsItApplying() {
		duplicateExclusionService.excludeFile(photo.getCatalogFilePublicId());

		long judged = photo.getContentRevision();

		replaceTheBytes();

		Assertions.assertThat(currentRevision()).as("the generation the file is on now").isEqualTo(judged + 1);

		Assertions.assertThat(storedRevisions()).as("what the user said is still on record, and says what it judged")
				.containsExactly(judged);

		Assertions.assertThat(duplicateExclusionService.excludedFilePublicIds())
				.as("a judgement about bytes that are gone hides nothing").isEmpty();
	}

	/**
	 * And the screen still shows it, marked as no longer bearing on the file - the
	 * difference between forgetting a preference and knowing what it was about.
	 */
	@Test
	void theManagementListShowsTheJudgementAndThatItNoLongerApplies() {
		duplicateExclusionService.excludeFile(photo.getCatalogFilePublicId());

		Assertions.assertThat(duplicateExclusionService.fileExclusions()).singleElement()
				.satisfies(view -> Assertions.assertThat(view.applies()).isTrue());

		replaceTheBytes();

		Assertions.assertThat(duplicateExclusionService.fileExclusions()).singleElement().satisfies(view -> {
			Assertions.assertThat(view.catalogFilePublicId()).isEqualTo(photo.getCatalogFilePublicId());
			Assertions.assertThat(view.applies()).isFalse();
		});
	}

	/**
	 * The file is back on the Duplicados screen, because the judgement about the
	 * old picture stopped applying. So the user judges the new one - and saying it
	 * has to work, or the button does nothing for as long as the file exists.
	 *
	 * <p>
	 * One row either way: the table allows one judgement per file, and the second
	 * statement is the one that carries the revision that matters.
	 */
	@Test
	void judgingTheNewBytesReplacesTheJudgementAboutTheOldOnes() {
		duplicateExclusionService.excludeFile(photo.getCatalogFilePublicId());

		replaceTheBytes();

		Assertions.assertThat(duplicateExclusionService.excludeFile(photo.getCatalogFilePublicId()))
				.as("the user is looking at the picture that is there now and saying it about that one").isTrue();

		Assertions.assertThat(storedRevisions()).containsExactly(currentRevision());

		Assertions.assertThat(duplicateExclusionService.excludedFilePublicIds())
				.containsExactly(photo.getCatalogFilePublicId());
	}

	/**
	 * Saying it twice about the same picture is saying it once: nothing to write,
	 * and nothing for the caller to report as a change.
	 */
	@Test
	void judgingTheSameBytesTwiceWritesNothingTheSecondTime() {
		Assertions.assertThat(duplicateExclusionService.excludeFile(photo.getCatalogFilePublicId())).isTrue();
		Assertions.assertThat(duplicateExclusionService.excludeFile(photo.getCatalogFilePublicId())).isFalse();

		Assertions.assertThat(storedRevisions()).containsExactly(photo.getContentRevision());
	}

	/**
	 * What the watcher does when the bytes behind a path are not the ones on
	 * record: the one door that advances the generation.
	 */
	private void replaceTheBytes() {
		// Written and forgotten first. What follows changes the row through the
		// content door, and a copy the session is still holding would be flushed back
		// over it.
		entityManager.flush();
		entityManager.clear();

		CatalogFile known = catalogFileRepository.findById(photo.getId()).orElseThrow();

		ContentOutcome outcome = contentReconciliation.reconcile(known, new ContentObservation(
				new ContentState(REPLACED, 2048L, SEEN_AT, null), CatalogEventSources.WATCHER, SEEN_AT));

		Assertions.assertThat(outcome).as("the fixture only proves anything if the generation really moved")
				.isEqualTo(ContentOutcome.APPLIED);

		entityManager.flush();
		entityManager.clear();
	}

	private Long currentRevision() {
		return jdbcTemplate.queryForObject("SELECT content_revision FROM catalog_file WHERE id = ?", Long.class,
				photo.getId());
	}

	/**
	 * Read straight from the table rather than through the query under test, so
	 * that "the row is still there" is not answered by the predicate deciding
	 * whether it applies.
	 */
	private List<Long> storedRevisions() {
		entityManager.flush();

		return jdbcTemplate.queryForList("SELECT content_revision FROM duplicate_exclusion_file WHERE "
				+ "catalog_file_id = ?", Long.class, photo.getId());
	}
}