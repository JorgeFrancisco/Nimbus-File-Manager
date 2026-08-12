package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnershipGuard;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * An old answer never wins.
 *
 * <p>
 * The shape recovery produces out of a worker that only looked dead: an
 * analysis runs for minutes, its lease lapses, the row goes back on the queue,
 * the <em>same</em> worker claims it again, and the first run wakes up holding a
 * finished result. Publishing is two updates - retire what is current, promote
 * this - and neither of them knows which analysis asked. Without a fence the
 * older result retires the newer one and takes its place, and nothing on either
 * row shows which was which.
 *
 * <p>
 * Against a real database and without a test transaction, because the
 * publication commits in one of its own: what is asserted is what is in the
 * table afterwards, never that a method was or was not called. The worker name
 * is identical on both sides - only the attempt number tells the two takings
 * apart.
 */
@SpringBootTest
@Testcontainers
class SimilarityPublicationFencingIntegrationTest {

	private static final String WORKER = "worker-that-came-back";

	private static final String PARAMETERS = "p".repeat(64);
	private static final String COMPOSITION = "c".repeat(64);

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

	/**
	 * The clock the application writes with, so what this test compares against is
	 * in the same frame as what production stored. {@code LocalDateTime.now()} reads
	 * the JVM's default zone while the row was written in the configured one, and
	 * on any machine where the two differ - every CI runner - a fresh lease looked
	 * hours expired and an expired one looked fresh.
	 */
	@Autowired
	private Clock clock;

	@Autowired
	private SimilarityPublisher publisher;

	@Autowired
	private SimilarityGroupingRepository groupingRepository;

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private ExecutionOwnershipGuard executionOwnershipGuard;

	@AfterEach
	void forgetEverything() {
		groupingRepository.deleteAll();
		executionRepository.deleteAll();
	}

	/**
	 * The rebuild that lost its turn, arriving after the one that replaced it has
	 * already answered.
	 */
	@Test
	void anAnalysisThatLostItsTurnDoesNotReplaceTheAnswerThatOvertookIt() {
		long executionId = claimedAt(1);

		ExecutionOwnership replaced = Takings.fenced(executionId, WORKER, 1, executionOwnershipGuard);

		SimilarityGrouping oldResult = publisher.build(result(), executionId);

		// Recovery gave the row back and the same worker took it again: the row now
		// carries the second attempt, and the first taking's number matches nothing.
		ExecutionOwnership current = takenAgainAt(executionId, 2);

		SimilarityGrouping newResult = publisher.build(result(), executionId);

		assertThat(publisher.publish(newResult, current)).as("the taking that holds the row publishes").isTrue();
		assertThat(statusOf(newResult)).isEqualTo(GroupingStatus.ACTIVE);

		assertThat(publisher.publish(oldResult, replaced)).as("and the one it replaced is refused").isFalse();

		assertThat(statusOf(newResult)).as("the newer answer was not retired by the older one")
				.isEqualTo(GroupingStatus.ACTIVE);
		assertThat(statusOf(oldResult)).as("and the older result never became an answer")
				.isEqualTo(GroupingStatus.BUILDING);
		assertThat(activeIds()).containsExactly(newResult.getId());
		assertThat(currentAnswerId()).isEqualTo(newResult.getId());
	}

	/**
	 * The incremental route reaches the same place by a different door. Its own
	 * condition - was this derived from the answer that is still current? - cannot
	 * tell the two takings apart, because both derived from the same one; only the
	 * pin can.
	 */
	@Test
	void aRegroupThatLostItsTurnIsRefusedEvenWhenItsOwnBaseIsStillCurrent() {
		long executionId = claimedAt(1);

		ExecutionOwnership replaced = Takings.fenced(executionId, WORKER, 1, executionOwnershipGuard);

		SimilarityGrouping standing = published();

		Long basedOn = publisher.currentAnswer(family());

		assertThat(basedOn).as("the base this regroup was derived from").isEqualTo(standing.getId());

		SimilarityGrouping regrouped = publisher.build(result(), executionId);

		takenAgainAt(executionId, 2);

		assertThat(publisher.publishIfStillBasedOn(regrouped, basedOn, replaced))
				.as("its base is untouched, and it is still refused - by the taking, not by the base").isFalse();

		assertThat(statusOf(standing)).as("the answer on screen was not retired").isEqualTo(GroupingStatus.ACTIVE);
		assertThat(statusOf(regrouped)).isEqualTo(GroupingStatus.BUILDING);
		assertThat(activeIds()).containsExactly(standing.getId());
	}

	/** And the taking that holds the row goes on publishing normally. */
	@Test
	void theTakingThatHoldsTheRowPublishesTheIncrementalResultAsBefore() {
		long executionId = claimedAt(1);

		ExecutionOwnership current = Takings.fenced(executionId, WORKER, 1, executionOwnershipGuard);

		SimilarityGrouping standing = published();

		SimilarityGrouping regrouped = publisher.build(result(), executionId);

		assertThat(publisher.publishIfStillBasedOn(regrouped, standing.getId(), current)).isTrue();

		assertThat(statusOf(standing)).isEqualTo(GroupingStatus.SUPERSEDED);
		assertThat(statusOf(regrouped)).isEqualTo(GroupingStatus.ACTIVE);
		assertThat(activeIds()).containsExactly(regrouped.getId());
	}

	/**
	 * A lease that ran out is the same refusal, reached without anybody having
	 * claimed the row again: the pin asks whether this taking is in force, and an
	 * expired lease says it is not.
	 */
	@Test
	void anAnalysisWhoseLeaseRanOutIsRefusedBeforeRecoveryHasEvenRun() {
		long executionId = claimedAt(1);

		ExecutionOwnership lapsed = Takings.fenced(executionId, WORKER, 1, executionOwnershipGuard);

		SimilarityGrouping standing = published();

		SimilarityGrouping late = publisher.build(result(), executionId);

		Execution row = executionRepository.findById(executionId).orElseThrow();

		row.setLeaseUntil(LocalDateTime.now(clock).minusMinutes(1));

		executionRepository.saveAndFlush(row);

		assertThat(publisher.publish(late, lapsed)).isFalse();

		assertThat(statusOf(standing)).isEqualTo(GroupingStatus.ACTIVE);
		assertThat(statusOf(late)).isEqualTo(GroupingStatus.BUILDING);
	}

	/**
	 * A row claimed and running, the way the dispatcher leaves one before a handler
	 * starts: taken by a name, at an attempt, with a lease that has not run out.
	 */
	private long claimedAt(int claimCount) {
		return executionRepository.saveAndFlush(Execution.builder().executionType(ExecutionType.SIMILARITY_PHOTO)
				.status(ExecutionStatus.RUNNING).recursive(false).executeFlag(true).claimedBy(WORKER)
				.claimCount(claimCount).leaseUntil(LocalDateTime.now(clock).plusMinutes(10)).build()).getId();
	}

	/** Recovery put the row back and the same worker took it again. */
	private ExecutionOwnership takenAgainAt(long executionId, int claimCount) {
		Execution row = executionRepository.findById(executionId).orElseThrow();

		row.setClaimCount(claimCount);
		row.setLeaseUntil(LocalDateTime.now(clock).plusMinutes(10));

		executionRepository.saveAndFlush(row);

		return Takings.fenced(executionId, WORKER, claimCount, executionOwnershipGuard);
	}

	private GroupingStatus statusOf(SimilarityGrouping grouping) {
		return groupingRepository.findById(grouping.getId()).orElseThrow().getStatus();
	}

	private List<Long> activeIds() {
		return groupingRepository.findAll().stream().filter(row -> row.getStatus() == GroupingStatus.ACTIVE)
				.map(SimilarityGrouping::getId).toList();
	}

	private Long currentAnswerId() {
		return publisher.currentAnswer(family());
	}

	private SimilarityGrouping published() {
		return groupingRepository.saveAndFlush(SimilarityGrouping.builder().publicId(UUID.randomUUID())
				.mediaType(FileType.PHOTO).algorithmId(FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1)
				.groupingVersion(SimilarityConstants.GROUPING_VERSION).parametersDigest(PARAMETERS)
				.compositionDigest(COMPOSITION).eligibleCount(120).analyzedCount(120).candidateLimit(8000)
				.selectionPolicy(SimilarityConstants.SELECTION_OLDEST_ELIGIBLE_FIRST).status(GroupingStatus.ACTIVE)
				.computedAt(LocalDateTime.now(clock).minusHours(1)).publishedAt(LocalDateTime.now(clock).minusHours(1))
				.groupCount(1).memberCount(2).build());
	}

	private SimilarityFamily family() {
		return new SimilarityFamily(FileType.PHOTO, FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1,
				SimilarityConstants.GROUPING_VERSION, PARAMETERS);
	}

	private SimilarityAnalysisResult result() {
		return new SimilarityAnalysisResult(family(),
				new SimilarityComposition(COMPOSITION, 120, 119, 8000,
						SimilarityConstants.SELECTION_OLDEST_ELIGIBLE_FIRST),
				List.of(new AnalyzedGroup(96, 2048L,
						List.of(new AnalyzedMember(UUID.randomUUID(), Verdict.KEEP, Reason.ORIGINAL),
								new AnalyzedMember(UUID.randomUUID(), Verdict.DELETE_CANDIDATE,
										Reason.DERIVATIVE)))));
	}
}