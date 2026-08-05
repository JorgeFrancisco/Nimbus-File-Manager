package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

/**
 * The invariants of {@code similarity_relation} that the database enforces
 * rather than the application.
 *
 * <p>
 * A relation is undirected, and the cheapest way to be sure of that is to make
 * the other spelling impossible instead of asking every writer to canonicalise:
 * the {@code first < second} check refuses B-A and, as a consequence, A-A. These
 * tests exist because a constraint that is never exercised is a constraint
 * nobody knows is missing - and because a duplicated relation would silently
 * change a group's floor.
 */
class SimilarityRelationSchemaIntegrationTest extends SharedPostgresIntegrationTest {

	private static final String ALGORITHM = "DCT_PHASH_256_V1";
	private static final int RADIUS = 96;
	private static final int MINIMUM = 95;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Test
	void storesACanonicalRelationAndReadsItBack() {
		Long first = catalogued("a");
		Long second = catalogued("b");

		insert(ordered(first, second), 97);

		assertThat(count()).isEqualTo(1);
	}

	/** The pair written the other way round is refused, not stored twice. */
	@Test
	void refusesTheReversedSpellingOfAPair() {
		Long first = catalogued("a");
		Long second = catalogued("b");

		Long[] canonical = ordered(first, second);

		assertThatThrownBy(() -> insert(new Long[] { canonical[1], canonical[0] }, 97))
				.hasMessageContaining("ck_similarity_relation_canonical");
	}

	/** A file cannot relate to itself, which the same check already prevents. */
	@Test
	void refusesAFileRelatedToItself() {
		Long file = catalogued("a");

		assertThatThrownBy(() -> insert(new Long[] { file, file }, 100))
				.hasMessageContaining("ck_similarity_relation_canonical");
	}

	@Test
	void refusesTheSameRelationTwiceForTheSameParameters() {
		Long[] pair = ordered(catalogued("a"), catalogued("b"));

		insert(pair, 97);

		assertThatThrownBy(() -> insert(pair, 96)).hasMessageContaining("uk_similarity_relation");
	}

	/**
	 * The same pair under different parameters is a different fact, and both are
	 * kept: an analysis at SSIM 90 and one at 95 do not share a relation set.
	 */
	@Test
	void keepsTheSamePairSeparatelyForEachParameterSet() {
		Long[] pair = ordered(catalogued("a"), catalogued("b"));

		insert(pair, ALGORITHM, RADIUS, 95, 97);
		insert(pair, ALGORITHM, RADIUS, 90, 97);
		insert(pair, ALGORITHM, 64, 95, 97);
		insert(pair, "OTHER_ALGORITHM_V1", RADIUS, 95, 97);

		assertThat(count()).isEqualTo(4);
	}

	@Test
	void refusesAPercentageOutsideTheScale() {
		Long[] pair = ordered(catalogued("a"), catalogued("b"));

		assertThatThrownBy(() -> insert(pair, 101)).hasMessageContaining("ck_similarity_relation_percent");
	}

	/**
	 * A file deleted for good takes its relations with it - they are facts about
	 * something that no longer exists. Exclusion is a different thing entirely and
	 * touches nothing here, which is what lets an un-exclusion reuse what was
	 * already computed.
	 */
	@Test
	void deletingTheFileRemovesItsRelations() {
		CatalogFile first = catalog("a");
		Long second = catalogued("b");

		insert(ordered(first.getId(), second), 97);

		catalogFileRepository.delete(first);
		catalogFileRepository.flush();

		assertThat(count()).isZero();
	}

	private Long[] ordered(Long first, Long second) {
		return first < second ? new Long[] { first, second } : new Long[] { second, first };
	}

	private void insert(Long[] pair, int percent) {
		insert(pair, ALGORITHM, RADIUS, MINIMUM, percent);
	}

	private void insert(Long[] pair, String algorithm, int radius, int minimum, int percent) {
		jdbcTemplate.update("""
				INSERT INTO similarity_relation (algorithm_id, max_distance, min_similarity, first_catalog_file_id,
					second_catalog_file_id, similarity_percent, computed_at)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""", algorithm, radius, minimum, pair[0], pair[1], percent, LocalDateTime.now());
	}

	private int count() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM similarity_relation", Integer.class);
	}

	private Long catalogued(String name) {
		return catalog(name).getId();
	}

	private CatalogFile catalog(String name) {
		String unique = name + System.nanoTime();

		return catalogFileRepository.saveAndFlush(CatalogFile.builder().fileKey(unique).fileName(unique + ".jpg")
				.extension("jpg").sizeBytes(1L).modifiedAt(LocalDateTime.now()).fileType(FileType.PHOTO).build());
	}
}