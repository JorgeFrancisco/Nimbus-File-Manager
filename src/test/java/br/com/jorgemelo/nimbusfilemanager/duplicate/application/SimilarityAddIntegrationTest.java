package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import br.com.jorgemelo.nimbusfilemanager.shared.TestPostgres;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.FingerprintAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.RelationParameters;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.SimilarityRelationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

/**
 * The arrival path against a real PostgreSQL, which is the only place several of
 * its claims can be checked at all.
 *
 * <p>
 * Three of them, specifically. The queries the incremental load added are
 * <em>native</em> - an {@code unnest} of a bound array, an {@code = ANY} over
 * another - and a native query is only known to work where it runs. The write
 * happens through a service annotated {@code readOnly}, and whether that reaches
 * the connection is a question about Spring and the driver rather than about
 * this code. And coverage is claimed in the same transaction as the relations it
 * accounts for, which is a statement about a transaction and not about a field.
 *
 * <p>
 * <b>Deliberately not {@code @Transactional}.</b> Wrapping the test would make
 * every service call join one transaction the test controls, which is precisely
 * the thing being checked - the boundaries would be the test's rather than the
 * product's. The rows are removed afterwards instead.
 */
@SpringBootTest
@Testcontainers
class SimilarityAddIntegrationTest {

	private static final int MINIMUM = 70;

	private static final long NEARBY = 7;
	private static final long ALPHA = 101;
	private static final long BETA = 202;

	private static final SimilarityProgressCallback SILENT = (_, _) -> {
	};

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = TestPostgres.container();

	@Autowired
	private PhotoSimilarityService photoSimilarityService;

	@Autowired
	private SimilarityRelationRepository relationRepository;

	@Autowired
	private MediaFingerprintRepository mediaFingerprintRepository;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void empty() {
		jdbcTemplate.update("DELETE FROM similarity_relation_coverage");
		jdbcTemplate.update("DELETE FROM similarity_relation");
		jdbcTemplate.update("DELETE FROM media_fingerprint");
		jdbcTemplate.update("DELETE FROM catalog_file");
	}

	/**
	 * The whole path, through the database it will run against: the newcomers are
	 * discovered, compared, and both the relations and the coverage are written.
	 *
	 * <p>
	 * That the write happens at all is the first thing being asserted. The service
	 * is {@code @Transactional(readOnly = true)} at class level, and PostgreSQL
	 * refuses writes in a transaction that reached it as read-only - so if the flag
	 * did reach the connection, this would fail rather than quietly do nothing.
	 */
	@Test
	void anArrivalWritesItsRelationsAndItsCoverageThroughTheRealDatabase() {
		long first = photo(ALPHA);
		long second = photo(ALPHA);
		long third = photo(BETA);

		photoSimilarityService.add(MINIMUM, SILENT);

		assertThat(relationCount()).as("the pair that looks alike, and only it").isEqualTo(1);
		assertThat(covered()).as("every file the run had in hand is incorporated").containsExactly(first, second,
				third);
	}

	/**
	 * The second run compares the newcomer against what is covered and leaves the
	 * old pairs alone - it does not recompute them, and it does not lose them.
	 */
	@Test
	void asecondArrivalAddsToWhatIsStoredWithoutRecomputingIt() {
		long first = photo(ALPHA);
		long second = photo(ALPHA);

		photoSimilarityService.add(MINIMUM, SILENT);

		LocalDateTime computedAt = firstComputedAt();

		long third = photo(ALPHA);

		photoSimilarityService.add(MINIMUM, SILENT);

		assertThat(relationCount()).as("the newcomer relates to both of them").isEqualTo(3);
		assertThat(covered()).containsExactly(first, second, third);
		assertThat(firstComputedAt()).as("the pair that already existed was not touched").isEqualTo(computedAt);
	}

	/**
	 * <b>The counterexample, against the real queries.</b> A file that is covered
	 * but no longer eligible - logically deleted here - is still what the newcomer
	 * is compared against, because it is still part of the relation universe.
	 * Comparing against the eligible set instead would leave the pair evaluated by
	 * nobody, and both files would then be covered for good.
	 */
	@Test
	void aCoveredFileThatIsNoLongerEligibleIsStillComparedAgainst() {
		long first = photo(ALPHA);

		photoSimilarityService.add(MINIMUM, SILENT);

		catalogFileRepository.findById(first).ifPresent(file -> {
			file.setLifecycleStatus(LifecycleStatus.DELETED);

			catalogFileRepository.saveAndFlush(file);
		});

		long second = photo(ALPHA);

		photoSimilarityService.add(MINIMUM, SILENT);

		assertThat(relationCount()).as("the pair was evaluated although one end was hidden").isEqualTo(1);
		assertThat(covered()).containsExactly(first, second);
		assertThat(notCovered()).as("and nothing eligible is left waiting").isEmpty();
	}

	/**
	 * Running the same arrival twice writes nothing the second time. Coverage is
	 * inserted with {@code ON CONFLICT DO NOTHING} and the relations are upserted
	 * by their key, so a run repeated after a crash re-states what it knew rather
	 * than failing on it.
	 */
	@Test
	void repeatingAnArrivalIsANoOpRatherThanAConstraintViolation() {
		photo(ALPHA);
		photo(ALPHA);

		photoSimilarityService.add(MINIMUM, SILENT);

		int relations = relationCount();
		List<Long> covered = covered();

		photoSimilarityService.add(MINIMUM, SILENT);

		assertThat(relationCount()).isEqualTo(relations);
		assertThat(covered()).isEqualTo(covered);
	}

	/**
	 * The one query nothing else exercises: which thresholds this installation has
	 * analysed, which is what an arrival consults to find out whose answer it has
	 * to bring up to date.
	 */
	@Test
	void theAnalysedThresholdsAreTheFamiliesSomebodyActuallyRan() {
		assertThat(photoSimilarityService.analysedThresholds()).as("nothing analysed, nothing to refresh").isEmpty();

		photo(ALPHA);

		photoSimilarityService.add(MINIMUM, SILENT);
		photoSimilarityService.add(95, SILENT);

		assertThat(photoSimilarityService.analysedThresholds()).containsExactly(MINIMUM, 95);
	}

	/**
	 * A file deleted for good takes its coverage with it, by the cascade the
	 * migration declares - so it does not linger as a claim about a file that no
	 * longer exists.
	 */
	@Test
	void purgingACatalogFileTakesItsCoverageWithIt() {
		long first = photo(ALPHA);
		long second = photo(ALPHA);

		photoSimilarityService.add(MINIMUM, SILENT);

		jdbcTemplate.update("DELETE FROM media_fingerprint WHERE catalog_file_id = ?", second);
		jdbcTemplate.update("DELETE FROM catalog_file WHERE id = ?", second);

		assertThat(covered()).containsExactly(first);
		assertThat(relationCount()).as("and its relations, by the same cascade").isZero();
	}

	private long photo(long look) {
		CatalogFile file = catalogFileRepository.saveAndFlush(CatalogFile.builder()
				.extension("jpg").sizeBytes(1L).modifiedAt(Instant.now())
				.fileType(FileType.PHOTO).build());

		mediaFingerprintRepository.saveAndFlush(MediaFingerprint.builder().catalogFileId(file.getId())
				.kind(FingerprintKind.PHOTO_PHASH).algorithm(FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1)
				.sampleIndex(0).hashBytes(PhotoSimilarityLibrary.hash(NEARBY, (int) (file.getId() % 16)))
				.sampleBytes(PhotoSimilarityLibrary.sample(look, 0)).computedAt(LocalDateTime.now()).build());

		return file.getId();
	}

	private int relationCount() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM similarity_relation", Integer.class);
	}

	private LocalDateTime firstComputedAt() {
		return jdbcTemplate.queryForObject("""
				SELECT computed_at FROM similarity_relation
				ORDER BY first_catalog_file_id, second_catalog_file_id LIMIT 1
				""", LocalDateTime.class);
	}

	private List<Long> covered() {
		return relationRepository.findCovered(FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1, 96, MINIMUM,
				RelationParameters.NO_MEDIA_PARAMETERS);
	}

	private List<Long> notCovered() {
		List<Long> eligible = mediaFingerprintRepository.findEligibleForSimilarity(FingerprintKind.PHOTO_PHASH.name(),
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1);

		return relationRepository.findEligibleNotCovered(FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1, 96, MINIMUM,
				RelationParameters.NO_MEDIA_PARAMETERS, eligible.toArray(Long[]::new));
	}
}