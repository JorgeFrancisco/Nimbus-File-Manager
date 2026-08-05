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

		Execution cancelled = executionRepository.saveAndFlush(askedForAndThenStopped(executionType));

		assertThat(awaitTerminal(cancelled.getId()).getStatus()).isEqualTo(ExecutionStatus.CANCELLED);

		// Nothing was written at all: not a BUILDING row left for the sweeper, not a
		// second ACTIVE, not a partial one.
		assertThat(groupingsFor(mediaType)).containsExactly(standing.getId());

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

	private List<Long> groupingsFor(FileType mediaType) {
		return similarityGroupingRepository.findAll().stream().filter(row -> row.getMediaType() == mediaType)
				.map(SimilarityGrouping::getId).toList();
	}

	/**
	 * A published answer of the kind the screen reads, so that retiring it would be
	 * an observable event rather than a no-op over an empty table.
	 */
	private SimilarityGrouping published(FileType mediaType) {
		return SimilarityGrouping.builder().publicId(UUID.randomUUID()).mediaType(mediaType)
				.algorithmId("STANDING_ANSWER_V1").groupingVersion(SimilarityConstants.GROUPING_VERSION)
				.parametersDigest(PARAMETERS).compositionDigest(COMPOSITION).eligibleCount(120).analyzedCount(118)
				.candidateLimit(8000).selectionPolicy(SimilarityConstants.SELECTION_OLDEST_FIRST)
				.status(GroupingStatus.ACTIVE).computedAt(LocalDateTime.now().minusDays(1))
				.publishedAt(LocalDateTime.now().minusDays(1)).groupCount(3).memberCount(7).build();
	}

	/**
	 * The request as it would be after somebody asked for it and then asked for it
	 * to stop. No expected digests: what is under test is the cancellation, and a
	 * definition that had also moved would end the run for the other reason.
	 */
	private Execution askedForAndThenStopped(ExecutionType type) {
		return Execution.builder().executionType(type).status(ExecutionStatus.PENDING).cancelRequested(true)
				.recursive(false).executeFlag(true)
				.requestPayload(executionPayloadCodec.encode(new SimilarityAnalysisPayload(
						DuplicateConstants.SIMILARITY_PAYLOAD_SCHEMA_VERSION, 70, null, null)))
				.build();
	}

	private Execution awaitTerminal(Long executionId) {
		return await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250)).until(
				() -> executionRepository.findById(executionId).orElseThrow(),
				execution -> execution.getStatus().isTerminal());
	}
}