package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.FingerprintAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.SimilarityConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedMember;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityComposition;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityFamily;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.GroupingStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Reason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Verdict;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGrouping;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.SimilarityGroupingRepository;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * What a half-finished or overtaken analysis is allowed to cost, against a real
 * database.
 *
 * <p>
 * Everything here is a property of durable state, which is why none of it can
 * be shown with a mock: the question is always what is in the table after
 * something went wrong, and whether the answer the screen was showing is still
 * there.
 *
 * <p>
 * Three moments matter, and they are the three the worker passes through. While
 * the result is being written, a failure must leave nothing at all. Once it is
 * written and before it is promoted, it must be invisible and the previous
 * answer untouched. At the promotion, a result derived from an answer that has
 * since been replaced must step aside - it drew its conclusions from relations
 * the newer analysis has already reconsidered.
 */
@SpringBootTest
@Testcontainers
class SimilarityRegroupPublicationIntegrationTest {

	private static final String PARAMETERS = "p".repeat(64);
	private static final String COMPOSITION = "c".repeat(64);

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

	@Autowired
	private SimilarityPublisher publisher;

	@Autowired
	private SimilarityGroupingRepository groupingRepository;

	@Autowired
	private ExecutionRepository executionRepository;

	@AfterEach
	void forgetEverything() {
		groupingRepository.deleteAll();
		executionRepository.deleteAll();
	}

	/**
	 * The ordinary case: it replaces exactly the answer it was derived from. The
	 * base is read the way the job reads it, rather than taken from the row the
	 * test happens to be holding - naming the current answer is itself part of what
	 * has to work.
	 */
	@Test
	void aRegroupReplacesTheAnswerItWasDerivedFrom() {
		SimilarityGrouping standing = published();

		Long basedOn = publisher.currentAnswer(family());

		assertThat(basedOn).isEqualTo(standing.getId());

		SimilarityGrouping regrouped = publisher.build(result(), ranBy());

		assertThat(publisher.publishIfStillBasedOn(regrouped, basedOn, Takings.unfenced(1L))).isTrue();

		assertThat(statusOf(standing)).isEqualTo(GroupingStatus.SUPERSEDED);
		assertThat(statusOf(regrouped)).isEqualTo(GroupingStatus.ACTIVE);
	}

	/** And a family nobody has answered yet names no base at all. */
	@Test
	void aFamilyWithNoPublishedAnswerNamesNoBase() {
		assertThat(publisher.currentAnswer(family())).isNull();
	}

	/**
	 * A rebuild finished first. The regroup read relations that the rebuild has
	 * since recomputed - and, unlike the regroup, the rebuild also compared the
	 * files that arrived. Publishing on top of it would swap a more complete answer
	 * for a less complete one, and nothing in either row would show that it had
	 * happened.
	 */
	@Test
	void aRegroupDoesNotReplaceAnAnswerThatArrivedWhileItWorked() {
		SimilarityGrouping standing = published();

		SimilarityGrouping regrouped = publisher.build(result(), ranBy());

		SimilarityGrouping rebuilt = publisher.build(result(), ranBy());

		assertThat(publisher.publish(rebuilt, Takings.unfenced(1L))).as("the rebuild gets there first").isTrue();

		assertThat(publisher.publishIfStillBasedOn(regrouped, standing.getId(), Takings.unfenced(1L))).isFalse();

		assertThat(statusOf(rebuilt)).isEqualTo(GroupingStatus.ACTIVE);
		assertThat(statusOf(regrouped)).as("discarded, not retried: the screen already has the better answer")
				.isEqualTo(GroupingStatus.BUILDING);
		assertThat(activeIds()).containsExactly(rebuilt.getId());
	}

	/**
	 * Having started from nothing is a claim about the world too. A family with no
	 * published answer is the case where relations exist but the grouping was
	 * swept or never published, and one appearing meanwhile is the same overtaking
	 * as any other.
	 */
	@Test
	void aRegroupDerivedFromNoAnswerIsRefusedWhenOneAppeared() {
		SimilarityGrouping regrouped = publisher.build(result(), ranBy());

		SimilarityGrouping rebuilt = publisher.build(result(), ranBy());

		publisher.publish(rebuilt, Takings.unfenced(1L));

		assertThat(publisher.publishIfStillBasedOn(regrouped, null, Takings.unfenced(1L))).isFalse();

		assertThat(statusOf(rebuilt)).isEqualTo(GroupingStatus.ACTIVE);
		assertThat(statusOf(regrouped)).isEqualTo(GroupingStatus.BUILDING);
	}

	/** And it publishes when the family is still unanswered. */
	@Test
	void aRegroupDerivedFromNoAnswerPublishesWhileThereStillIsNone() {
		SimilarityGrouping regrouped = publisher.build(result(), ranBy());

		assertThat(publisher.publishIfStillBasedOn(regrouped, null, Takings.unfenced(1L))).isTrue();

		assertThat(statusOf(regrouped)).isEqualTo(GroupingStatus.ACTIVE);
	}

	/**
	 * The worker dies after the result is written and before it is promoted -
	 * which is the widest window there is, because writing thousands of groups
	 * takes as long as it takes. What is left is a row nobody reads, and the answer
	 * the screen was showing is still the answer.
	 */
	@Test
	void aResultWrittenButNeverPromotedLeavesThePreviousAnswerStanding() {
		SimilarityGrouping standing = published();

		SimilarityGrouping abandoned = publisher.build(result(), ranBy());

		assertThat(statusOf(abandoned)).isEqualTo(GroupingStatus.BUILDING);
		assertThat(activeIds()).containsExactly(standing.getId());
		assertThat(groupingRepository.findActive(FileType.PHOTO, FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1,
				SimilarityConstants.GROUPING_VERSION, PARAMETERS)).get()
				.extracting(SimilarityGrouping::getId).isEqualTo(standing.getId());
	}

	/**
	 * The worker dies in the middle of writing it down, with the grouping row and
	 * some of its groups already inserted. One transaction covers the lot, so what
	 * survives is nothing - there is no partial result for a sweeper to find and
	 * none for a reader to stumble on.
	 */
	@Test
	void aResultThatFailsHalfWayThroughBeingWrittenLeavesNothingBehind() {
		SimilarityGrouping standing = published();

		Long execution = ranBy();

		SimilarityAnalysisResult unwritable = resultWithAMemberThatCannotBeStored();

		assertThatThrownBy(() -> publisher.build(unwritable, execution)).isInstanceOf(DataAccessException.class);

		assertThat(groupingRepository.findAll()).extracting(SimilarityGrouping::getId)
				.containsExactly(standing.getId());
		assertThat(statusOf(standing)).isEqualTo(GroupingStatus.ACTIVE);
	}

	/**
	 * A real execution row, because the grouping points at one. Analyses are
	 * claimed from the queue, so a published result always names the run that
	 * produced it - and the foreign key says so.
	 */
	private Long ranBy() {
		return executionRepository.saveAndFlush(Execution.builder().executionType(ExecutionType.SIMILARITY_PHOTO)
				.status(ExecutionStatus.RUNNING).recursive(false).executeFlag(true).build()).getId();
	}

	private GroupingStatus statusOf(SimilarityGrouping grouping) {
		return groupingRepository.findById(grouping.getId()).orElseThrow().getStatus();
	}

	private List<Long> activeIds() {
		return groupingRepository.findAll().stream().filter(row -> row.getStatus() == GroupingStatus.ACTIVE)
				.map(SimilarityGrouping::getId).toList();
	}

	private SimilarityGrouping published() {
		return groupingRepository.saveAndFlush(SimilarityGrouping.builder().publicId(UUID.randomUUID())
				.mediaType(FileType.PHOTO).algorithmId(FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1)
				.groupingVersion(SimilarityConstants.GROUPING_VERSION).parametersDigest(PARAMETERS)
				.compositionDigest(COMPOSITION).eligibleCount(120).analyzedCount(120).candidateLimit(8000)
				.selectionPolicy(SimilarityConstants.SELECTION_OLDEST_ELIGIBLE_FIRST).status(GroupingStatus.ACTIVE)
				.computedAt(LocalDateTime.now().minusHours(1)).publishedAt(LocalDateTime.now().minusHours(1))
				.groupCount(1).memberCount(2).build());
	}

	private SimilarityFamily family() {
		return new SimilarityFamily(FileType.PHOTO, FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1,
				SimilarityConstants.GROUPING_VERSION, PARAMETERS);
	}

	private SimilarityAnalysisResult result() {
		return resultOf(new AnalyzedMember(UUID.randomUUID(), Verdict.DELETE_CANDIDATE, Reason.DERIVATIVE));
	}

	/**
	 * A member with no public id, which the column refuses. It is the cheapest
	 * honest way to fail after the grouping and its groups are already inserted -
	 * the members are written last.
	 */
	private SimilarityAnalysisResult resultWithAMemberThatCannotBeStored() {
		return resultOf(new AnalyzedMember(null, Verdict.DELETE_CANDIDATE, Reason.DERIVATIVE));
	}

	private SimilarityAnalysisResult resultOf(AnalyzedMember second) {
		return new SimilarityAnalysisResult(family(),
				new SimilarityComposition(COMPOSITION, 120, 119, 8000,
						SimilarityConstants.SELECTION_OLDEST_ELIGIBLE_FIRST),
				List.of(new AnalyzedGroup(96, 2048L,
						List.of(new AnalyzedMember(UUID.randomUUID(), Verdict.KEEP, Reason.ORIGINAL), second))));
	}
}