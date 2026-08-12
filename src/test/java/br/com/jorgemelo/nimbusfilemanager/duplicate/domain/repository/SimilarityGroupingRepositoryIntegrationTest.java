package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import jakarta.persistence.EntityManager;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.FingerprintAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.SimilarityConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.GroupingStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGrouping;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;

/**
 * The claim that a screen never sees two answers at once, and never sees a
 * half-written one, rests on two things the database enforces rather than the
 * code: a partial unique index over the ACTIVE rows of a family, and a
 * publication that only moves a row out of BUILDING. Neither can be proved with
 * a mock, so this runs against a real PostgreSQL.
 */
class SimilarityGroupingRepositoryIntegrationTest extends SharedPostgresIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, Month.MAY, 1, 10, 0);
	private static final String PARAMETERS = "p".repeat(64);

	@Autowired
	private SimilarityGroupingRepository repository;

	@Autowired
	private EntityManager entityManager;

	@Test
	void aFamilyCannotHaveTwoActiveAnswersAtOnce() {
		repository.saveAndFlush(grouping("first", GroupingStatus.ACTIVE));

		SimilarityGrouping second = grouping("second", GroupingStatus.ACTIVE);

		Assertions.assertThatThrownBy(() -> repository.saveAndFlush(second))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void severalGroupingsMayBeBuildingOrSupersededAtTheSameTime() {
		repository.saveAndFlush(grouping("building-a", GroupingStatus.BUILDING));
		repository.saveAndFlush(grouping("building-b", GroupingStatus.BUILDING));
		repository.saveAndFlush(grouping("retired-a", GroupingStatus.SUPERSEDED));
		repository.saveAndFlush(grouping("retired-b", GroupingStatus.SUPERSEDED));

		Assertions.assertThat(repository.findByStatusOrderByComputedAtAsc(GroupingStatus.BUILDING)).hasSize(2);
		Assertions.assertThat(repository.findActive(FileType.PHOTO,
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1, SimilarityConstants.GROUPING_VERSION, PARAMETERS))
				.isEmpty();
	}

	@Test
	void publishingRetiresThePreviousAnswerAndLeavesExactlyOneActive() {
		SimilarityGrouping previous = repository.saveAndFlush(grouping("previous", GroupingStatus.ACTIVE));
		SimilarityGrouping next = repository.saveAndFlush(grouping("next", GroupingStatus.BUILDING));

		Assertions.assertThat(repository.supersedeActive(FileType.PHOTO,
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1, SimilarityConstants.GROUPING_VERSION, PARAMETERS))
				.isEqualTo(1);
		Assertions.assertThat(repository.publish(next.getId(), NOW)).isEqualTo(1);

		entityManager.clear();

		SimilarityGrouping active = repository.findActive(FileType.PHOTO,
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1, SimilarityConstants.GROUPING_VERSION, PARAMETERS)
				.orElseThrow();

		Assertions.assertThat(active.getId()).isEqualTo(next.getId());
		Assertions.assertThat(active.getPublishedAt()).isEqualTo(NOW);
		Assertions.assertThat(repository.findById(previous.getId()).orElseThrow().getStatus())
				.isEqualTo(GroupingStatus.SUPERSEDED);
	}

	@Test
	void onlyTheFirstOfTwoRacingPublicationsOfTheSameGroupingWins() {
		SimilarityGrouping building = repository.saveAndFlush(grouping("racing", GroupingStatus.BUILDING));

		// The second call is the loser of the race: the row is no longer BUILDING,
		// the conditional update matches nothing, and the caller is told so rather
		// than publishing the same answer twice.
		Assertions.assertThat(repository.publish(building.getId(), NOW)).isEqualTo(1);
		Assertions.assertThat(repository.publish(building.getId(), NOW.plusMinutes(1))).isZero();

		entityManager.clear();

		Assertions.assertThat(repository.findById(building.getId()).orElseThrow().getPublishedAt()).isEqualTo(NOW);
	}

	@Test
	void aBuildThatNeverPublishesLeavesThePreviousAnswerOnScreen() {
		SimilarityGrouping previous = repository.saveAndFlush(grouping("previous", GroupingStatus.ACTIVE));

		repository.saveAndFlush(grouping("abandoned", GroupingStatus.BUILDING));

		Assertions.assertThat(repository
				.findActive(FileType.PHOTO, FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1,
						SimilarityConstants.GROUPING_VERSION, PARAMETERS)
				.orElseThrow().getId()).isEqualTo(previous.getId());
	}

	@Test
	void anAnalysisWithDifferentParametersIsADifferentFamilyAndMayBeActiveToo() {
		repository.saveAndFlush(grouping("photos-70", GroupingStatus.ACTIVE));

		SimilarityGrouping stricter = grouping("photos-95", GroupingStatus.ACTIVE);

		stricter.setParametersDigest("q".repeat(64));

		Assertions.assertThatCode(() -> repository.saveAndFlush(stricter)).doesNotThrowAnyException();

		Assertions.assertThat(repository.findActive(FileType.PHOTO,
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1, SimilarityConstants.GROUPING_VERSION, PARAMETERS))
				.isPresent();
	}

	@Test
	void abandonedBuildsAreFoundByAgeSoTheyCanBeReclaimed() {
		SimilarityGrouping old = grouping("stale", GroupingStatus.BUILDING);

		old.setComputedAt(NOW.minusDays(2));

		repository.saveAndFlush(old);
		repository.saveAndFlush(grouping("fresh", GroupingStatus.BUILDING));

		List<SimilarityGrouping> stale = repository.findByStatusAndComputedAtBefore(GroupingStatus.BUILDING,
				NOW.minusDays(1));

		Assertions.assertThat(stale).extracting(SimilarityGrouping::getCompositionDigest)
				.containsExactly(digest("stale"));
	}

	private SimilarityGrouping grouping(String name, GroupingStatus status) {
		return SimilarityGrouping.builder().similarityGroupingPublicId(UUID.randomUUID()).mediaType(FileType.PHOTO)
				.algorithmId(FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1)
				.groupingVersion(SimilarityConstants.GROUPING_VERSION).parametersDigest(PARAMETERS)
				.compositionDigest(digest(name)).eligibleCount(10).analyzedCount(10).candidateLimit(8000)
				.selectionPolicy(SimilarityConstants.SELECTION_OLDEST_ELIGIBLE_FIRST).status(status).computedAt(NOW)
				.groupCount(1).memberCount(2).build();
	}

	private static String digest(String name) {
		return (name + "-").repeat(64).substring(0, 64);
	}
}