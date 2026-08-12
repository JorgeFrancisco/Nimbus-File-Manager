package br.com.jorgemelo.nimbusfilemanager.telemetry.application;

import java.time.LocalDateTime;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

import br.com.jorgemelo.nimbusfilemanager.shared.TestPostgres;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnershipGuard;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhaseType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExternalToolCategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.model.ExecutionMetrics;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.model.ExecutionMetricsCategory;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.repository.ExecutionMetricsCategoryRepository;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.repository.ExecutionMetricsRepository;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.repository.ExecutionPhaseRepository;

/**
 * Telemetry written by a taking that no longer holds the row.
 *
 * <p>
 * Recovery runs on a timer, so this is ordinary rather than exotic: a worker
 * pauses long enough for its lease to lapse, the row is put back, another
 * attempt claims it and finishes, and only then does the first one come back to
 * write down what it measured. Its numbers are real and they describe a run
 * that happened - and they must not land, because a later attempt has already
 * said what this execution cost.
 *
 * <p>
 * The sharp half is not that the stale attempt fails to add its own rows. It is
 * that consolidation <em>replaces</em>: it clears the phases and categories
 * before writing. An unfenced stale write would therefore delete the current
 * attempt's measurements even if every insert it attempted then failed. That is
 * why the fence is the first statement of the transaction rather than a check
 * around it.
 *
 * <p>
 * Its own container and no test transaction: the consolidation commits, and the
 * point is what survives the commit.
 */
@SpringBootTest
@Testcontainers
class ExecutionTelemetryFencingIntegrationTest {

	private static final String WORKER = "worker-that-came-back";

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = TestPostgres.container();

	@Autowired
	private PerformanceTelemetryService performanceTelemetryService;

	@Autowired
	private ExecutionRepository executionRepository;

	@Autowired
	private ExecutionMetricsRepository executionMetricsRepository;

	@Autowired
	private ExecutionPhaseRepository executionPhaseRepository;

	@Autowired
	private ExecutionMetricsCategoryRepository executionMetricsCategoryRepository;

	@Autowired
	private ExecutionOwnershipGuard executionOwnershipGuard;

	@AfterEach
	void forgetEverything() {
		executionMetricsCategoryRepository.deleteAll();
		executionPhaseRepository.deleteAll();
		executionMetricsRepository.deleteAll();
		executionRepository.deleteAll();
	}

	/** A: the attempt that holds the row writes, and the row says which. */
	@Test
	void theCurrentAttemptWritesItsMeasurements() {
		long execution = finishedAt(1, ExecutionStatus.FINISHED);

		Assertions.assertThat(consolidate(execution, 1, measuring(7, ExternalToolCategory.FFMPEG_PHOTO_HASH))).isTrue();

		Assertions.assertThat(aggregate(execution).getAttemptClaimCount()).isEqualTo(1);
		Assertions.assertThat(aggregate(execution).getTasksExecuted()).isEqualTo(7);
	}

	/** B: reclaimed, and the newer attempt is the one the row ends up carrying. */
	@Test
	void afterAReclaimTheNewerAttemptWins() {
		long execution = finishedAt(1, ExecutionStatus.FINISHED);

		consolidate(execution, 1, measuring(7, ExternalToolCategory.FFMPEG_PHOTO_HASH));

		takenAgainAt(execution, 2);

		Assertions.assertThat(consolidate(execution, 2, measuring(30, ExternalToolCategory.FFPROBE_VIDEO))).isTrue();

		Assertions.assertThat(aggregate(execution).getAttemptClaimCount()).isEqualTo(2);
		Assertions.assertThat(aggregate(execution).getTasksExecuted()).isEqualTo(30);
	}

	/** C: the superseded attempt writes nothing at all. */
	@Test
	void aSupersededAttemptChangesNothing() {
		long execution = finishedAt(2, ExecutionStatus.FINISHED);

		ExecutionOwnership stale = Takings.fenced(execution, WORKER, 1, executionOwnershipGuard);

		takenAgainAt(execution, 2);

		Assertions.assertThat(performanceTelemetryService.recordMetrics(stale,
				measuring(7, ExternalToolCategory.FFMPEG_PHOTO_HASH), null)).isFalse();

		Assertions.assertThat(executionMetricsRepository.findById(execution)).isEmpty();
		Assertions.assertThat(phasesOf(execution)).isEmpty();
		Assertions.assertThat(categoriesOf(execution)).isEmpty();
	}

	/**
	 * D and E together, and the reason the fence is where it is: the current
	 * attempt has already consolidated, and the stale one arrives afterwards. It
	 * must neither overwrite the aggregate nor - the part an "insert-only" fence
	 * would miss - delete the phases and categories that are there.
	 */
	@Test
	void aStaleAttemptArrivingLateDeletesNothingTheCurrentOneWrote() {
		long execution = finishedAt(1, ExecutionStatus.FINISHED);

		ExecutionOwnership stale = Takings.fenced(execution, WORKER, 1, executionOwnershipGuard);

		takenAgainAt(execution, 2);

		consolidate(execution, 2, measuring(30, ExternalToolCategory.FFPROBE_VIDEO));

		performanceTelemetryService.recordMetrics(stale, measuring(7, ExternalToolCategory.FFMPEG_PHOTO_HASH), null);

		Assertions.assertThat(aggregate(execution).getAttemptClaimCount()).isEqualTo(2);
		Assertions.assertThat(aggregate(execution).getTasksExecuted()).isEqualTo(30);

		Assertions.assertThat(phasesOf(execution)).singleElement()
				.satisfies(phase -> Assertions.assertThat(phase.getItems()).isEqualTo(30));

		Assertions.assertThat(categoriesOf(execution)).singleElement().satisfies(
				row -> Assertions.assertThat(row.getCategory()).isEqualTo(ExternalToolCategory.FFPROBE_VIDEO));
	}

	/** F: a run that ended in error still says what it cost before it failed. */
	@Test
	void anExecutionThatFailedKeepsItsTelemetry() {
		long execution = finishedAt(1, ExecutionStatus.ERROR);

		Assertions.assertThat(consolidate(execution, 1, measuring(3, ExternalToolCategory.FFMPEG_PHOTO_HASH))).isTrue();

		Assertions.assertThat(aggregate(execution).getTasksExecuted()).isEqualTo(3);
	}

	/** G: and so does a cancelled one - it did the work it did. */
	@Test
	void aCancelledExecutionKeepsItsTelemetry() {
		long execution = finishedAt(1, ExecutionStatus.CANCELLED);

		Assertions.assertThat(consolidate(execution, 1, measuring(5, ExternalToolCategory.FFMPEG_VIDEO_FRAME)))
				.isTrue();

		Assertions.assertThat(aggregate(execution).getTasksExecuted()).isEqualTo(5);
	}

	/** H and I: two runs never mix, whether or not they are the same kind. */
	@Test
	void twoExecutionsKeepTheirOwnNumbers() {
		long photos = finishedAt(1, ExecutionStatus.FINISHED, ExecutionType.FINGERPRINT_PHOTO);
		long videos = finishedAt(1, ExecutionStatus.FINISHED, ExecutionType.FINGERPRINT_VIDEO);
		long alsoPhotos = finishedAt(1, ExecutionStatus.FINISHED, ExecutionType.FINGERPRINT_PHOTO);

		consolidate(photos, 1, measuring(7, ExternalToolCategory.FFMPEG_PHOTO_HASH));
		consolidate(videos, 1, measuring(30, ExternalToolCategory.FFPROBE_VIDEO));
		consolidate(alsoPhotos, 1, measuring(11, ExternalToolCategory.FFMPEG_PHOTO_HASH));

		Assertions.assertThat(aggregate(photos).getTasksExecuted()).isEqualTo(7);
		Assertions.assertThat(aggregate(videos).getTasksExecuted()).isEqualTo(30);
		Assertions.assertThat(aggregate(alsoPhotos).getTasksExecuted()).isEqualTo(11);

		Assertions.assertThat(categoriesOf(photos)).singleElement().satisfies(
				row -> Assertions.assertThat(row.getCategory()).isEqualTo(ExternalToolCategory.FFMPEG_PHOTO_HASH));
		Assertions.assertThat(categoriesOf(videos)).singleElement().satisfies(
				row -> Assertions.assertThat(row.getCategory()).isEqualTo(ExternalToolCategory.FFPROBE_VIDEO));
	}

	/**
	 * The replacement a second attempt performs is total: what the first measured
	 * is gone, not merged. A category the newer run never touched must not linger
	 * from the older one, or the report would credit it with a tool it never used.
	 */
	@Test
	void aSecondAttemptReplacesTheCategoriesRatherThanAddingToThem() {
		long execution = finishedAt(1, ExecutionStatus.FINISHED);

		consolidate(execution, 1, measuring(7, ExternalToolCategory.FFMPEG_PHOTO_HASH));

		takenAgainAt(execution, 2);

		consolidate(execution, 2, measuring(30, ExternalToolCategory.FFPROBE_VIDEO));

		Assertions.assertThat(categoriesOf(execution)).singleElement().satisfies(
				row -> Assertions.assertThat(row.getCategory()).isEqualTo(ExternalToolCategory.FFPROBE_VIDEO));
	}

	private boolean consolidate(long executionId, int claimCount, ExecutionMetricsContext context) {
		return performanceTelemetryService
				.recordMetrics(Takings.fenced(executionId, WORKER, claimCount, executionOwnershipGuard), context, null);
	}

	/** A context that measured a given amount of work through one tool. */
	private ExecutionMetricsContext measuring(long tasks, ExternalToolCategory category) {
		ExecutionMetricsContext context = new ExecutionMetricsContext();

		for (long task = 0; task < tasks; task++) {
			context.processing().incExecuted();
		}

		context.processing().recordExternalExec(category, 8_000_000L);
		context.phases().addNanos(ExecutionPhaseType.EXTRACTION, 3_000_000L);
		context.phases().addItems(ExecutionPhaseType.EXTRACTION, tasks);

		return context;
	}

	private long finishedAt(int claimCount, ExecutionStatus status) {
		return finishedAt(claimCount, status, ExecutionType.FINGERPRINT_PHOTO);
	}

	private long finishedAt(int claimCount, ExecutionStatus status, ExecutionType type) {
		return executionRepository.saveAndFlush(Execution.builder().executionType(type).status(status).recursive(false)
				.executeFlag(true).claimedBy(WORKER).claimCount(claimCount)
				.startedAt(LocalDateTime.now().minusMinutes(2)).finishedAt(LocalDateTime.now()).build()).getId();
	}

	/** Recovery put the row back and the same worker took it again. */
	private void takenAgainAt(long executionId, int claimCount) {
		Execution row = executionRepository.findById(executionId).orElseThrow();

		row.setClaimCount(claimCount);

		executionRepository.saveAndFlush(row);
	}

	private ExecutionMetrics aggregate(long executionId) {
		return executionMetricsRepository.findById(executionId).orElseThrow();
	}

	private List<ExecutionPhase> phasesOf(long executionId) {
		return executionPhaseRepository.findByExecutionIdOrderByPhaseAsc(executionId);
	}

	private List<ExecutionMetricsCategory> categoriesOf(long executionId) {
		return executionMetricsCategoryRepository.findByExecutionIdOrderByCategoryAsc(executionId);
	}
}