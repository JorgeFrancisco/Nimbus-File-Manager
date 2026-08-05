package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.SimilarityConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisPayload;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.GroupingStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.SimilarityRunMode;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGrouping;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.SimilarityGroupingRepository;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * Cancelling a similarity analysis, against a real database and a real worker.
 *
 * <p>
 * What has to be true is a property of durable state rather than of a call, so
 * a mock cannot show it: the row reaches a terminal status of CANCELLED, no
 * grouping is written, and the answer the Duplicados screen was already showing
 * is still the answer afterwards. Retiring the previous grouping is a statement
 * the publication runs, so "it was not retired" only means anything if there was
 * a real one to retire and a real transaction that did not touch it.
 *
 * <p>
 * The stop is requested before the row is ever claimed, which is deliberate and
 * not a shortcut around timing: {@code cancel_requested} is a column, the worker
 * reads it from the database rather than from anybody's memory, and an analysis
 * that honours it only when the click lands inside a particular millisecond
 * would not be honouring it at all. Racing a real click would test the clock.
 */
@SpringBootTest
@ActiveProfiles(NimbusProfiles.APP_WORKER_COMBINED)
@Testcontainers
class SimilarityCancellationIntegrationTest {

	private static final String PARAMETERS = "p".repeat(64);
	private static final String REGROUP_PARAMETERS = "r".repeat(64);
	private static final String COMPOSITION = "c".repeat(64);

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private ExecutionPayloadCodec executionPayloadCodec;

	@Autowired
	private SimilarityGroupingRepository similarityGroupingRepository;

	/**
	 * Both media, because the two handlers differ in the analyser they carry and in
	 * nothing else - and "equivalent behaviour" is a claim about both of them.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "SIMILARITY_PHOTO", "SIMILARITY_VIDEO" })
	void endsAsCancelledWithoutPublishingAnythingOrRetiringWhatWasThere(String type) {
		ExecutionType executionType = ExecutionType.valueOf(type);

		FileType mediaType = executionType == ExecutionType.SIMILARITY_PHOTO ? FileType.PHOTO : FileType.VIDEO;

		SimilarityGrouping standing = similarityGroupingRepository.saveAndFlush(published(mediaType));

		List<Long> before = groupingsFor(mediaType);

		Execution cancelled = executionRepository.saveAndFlush(askedForAndThenStopped(executionType));

		assertThat(awaitTerminal(cancelled.getId()).getStatus()).isEqualTo(ExecutionStatus.CANCELLED);

		// Nothing was written at all: not a BUILDING row left for the sweeper, not a
		// second ACTIVE, not a partial one. Compared against what was there a moment
		// earlier rather than against a single id, because the methods of this class
		// share a database and each plants its own published answer.
		assertThat(groupingsFor(mediaType)).isEqualTo(before);

		assertThat(similarityGroupingRepository.findById(standing.getId()).orElseThrow().getStatus())
				.isEqualTo(GroupingStatus.ACTIVE);
	}

	/**
	 * The same promise for the incremental route, which reaches its answer by a
	 * different road and arrives at the same publication. A regroup is cheap enough
	 * that stopping it saves little, but what it must never do is cost the user the
	 * analysis they already had - and it retires that answer at exactly the same
	 * point a rebuild does, so the cancellation has to land before it just the
	 * same.
	 */
	@Test
	void aCancelledRegroupLeavesTheStandingAnswerExactlyWhereItWas() {
		SimilarityGrouping standing = similarityGroupingRepository
				.saveAndFlush(published(FileType.PHOTO, REGROUP_PARAMETERS));

		List<Long> before = groupingsFor(FileType.PHOTO);

		Execution cancelled = executionRepository.saveAndFlush(
				askedForAndThenStopped(ExecutionType.SIMILARITY_PHOTO, SimilarityRunMode.REGROUP));

		assertThat(awaitTerminal(cancelled.getId()).getStatus()).isEqualTo(ExecutionStatus.CANCELLED);

		assertThat(groupingsFor(FileType.PHOTO)).isEqualTo(before);

		assertThat(similarityGroupingRepository.findById(standing.getId()).orElseThrow().getStatus())
				.isEqualTo(GroupingStatus.ACTIVE);
	}

	/** And the row says cancelled, never error: a person pressed a button. */
	@Test
	void doesNotRecordACancelledAnalysisAsAFailure() {
		Execution cancelled = executionRepository
				.saveAndFlush(askedForAndThenStopped(ExecutionType.SIMILARITY_PHOTO));

		Execution terminal = awaitTerminal(cancelled.getId());

		assertThat(terminal.getStatus()).isNotIn(ExecutionStatus.ERROR, ExecutionStatus.FINISHED_WITH_ERRORS);
		assertThat(terminal.getClaimCount()).isEqualTo(1);
	}

	/** Sorted, so that comparing two readings compares content and not row order. */
	private List<Long> groupingsFor(FileType mediaType) {
		return similarityGroupingRepository.findAll().stream().filter(row -> row.getMediaType() == mediaType)
				.map(SimilarityGrouping::getId).sorted().toList();
	}

	/**
	 * A published answer of the kind the screen reads, so that retiring it would be
	 * an observable event rather than a no-op over an empty table.
	 */
	private SimilarityGrouping published(FileType mediaType) {
		return published(mediaType, PARAMETERS);
	}

	/**
	 * Its own family per test, because these methods share a database and a
	 * partial unique index allows one ACTIVE per family. Two tests each standing up
	 * their own published answer for photos would collide on the index rather than
	 * on anything they are about.
	 */
	private SimilarityGrouping published(FileType mediaType, String parametersDigest) {
		return SimilarityGrouping.builder().publicId(UUID.randomUUID()).mediaType(mediaType)
				.algorithmId("STANDING_ANSWER_V1").groupingVersion(SimilarityConstants.GROUPING_VERSION)
				.parametersDigest(parametersDigest).compositionDigest(COMPOSITION).eligibleCount(120).analyzedCount(118)
				.candidateLimit(8000).selectionPolicy(SimilarityConstants.SELECTION_OLDEST_ELIGIBLE_FIRST)
				.status(GroupingStatus.ACTIVE).computedAt(LocalDateTime.now().minusDays(1))
				.publishedAt(LocalDateTime.now().minusDays(1)).groupCount(3).memberCount(7).build();
	}

	/**
	 * The request as it would be after somebody asked for it and then asked for it
	 * to stop. No expected digests: what is under test is the cancellation, and a
	 * definition that had also moved would end the run for the other reason.
	 */
	private Execution askedForAndThenStopped(ExecutionType type) {
		return askedForAndThenStopped(type, SimilarityRunMode.REBUILD);
	}

	private Execution askedForAndThenStopped(ExecutionType type, SimilarityRunMode mode) {
		return Execution.builder().executionType(type).status(ExecutionStatus.PENDING).cancelRequested(true)
				.recursive(false).executeFlag(true)
				.requestPayload(executionPayloadCodec.encode(new SimilarityAnalysisPayload(
						DuplicateConstants.SIMILARITY_PAYLOAD_SCHEMA_VERSION, 70, null, null, mode)))
				.build();
	}

	private Execution awaitTerminal(Long executionId) {
		return await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250)).until(
				() -> executionRepository.findById(executionId).orElseThrow(),
				execution -> execution.getStatus().isTerminal());
	}
}