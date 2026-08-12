package br.com.jorgemelo.nimbusfilemanager.telemetry.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhaseType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExternalToolCategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.ConfigSnapshot;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.model.ExecutionMetrics;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.model.ExecutionMetricsCategory;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.repository.ExecutionMetricsCategoryRepository;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.repository.ExecutionMetricsRepository;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.repository.ExecutionPhaseRepository;

/**
 * The write side of telemetry, against mocks. What it can prove here is the
 * shape of the write - what is fenced, what is replaced, what a refused fence
 * leaves alone. That the fence actually holds under a concurrent reclaim is a
 * question for PostgreSQL, and lives in the integration tests.
 */
@ExtendWith(MockitoExtension.class)
class PerformanceTelemetryServiceTest {

	@Mock
	private ExecutionRepository executionRepository;

	@Mock
	private ExecutionMetricsRepository executionMetricsRepository;

	@Mock
	private ExecutionPhaseRepository executionPhaseRepository;

	@Mock
	private ExecutionMetricsCategoryRepository executionMetricsCategoryRepository;

	private PerformanceTelemetryService service(String version, ZoneId zone) {
		return new PerformanceTelemetryService(executionRepository, executionMetricsRepository,
				executionPhaseRepository, executionMetricsCategoryRepository, version, Clock.system(zone));
	}

	/** A taking that is still the row's, on the attempt it says it is. */
	private ExecutionOwnership current(long executionId, int claimCount) {
		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		when(ownership.pinAttempt()).thenReturn(true);
		when(ownership.executionId()).thenReturn(executionId);
		// Never reached when the execution is gone, which one test is about.
		lenient().when(ownership.claimCount()).thenReturn(claimCount);

		return ownership;
	}

	/** A taking a later attempt has replaced: the fence answers no. */
	private ExecutionOwnership superseded() {
		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		when(ownership.pinAttempt()).thenReturn(false);

		return ownership;
	}

	private ExecutionMetrics savedMetrics() {
		ArgumentCaptor<ExecutionMetrics> captor = ArgumentCaptor.captor();

		verify(executionMetricsRepository).save(captor.capture());

		return captor.getValue();
	}

	private Execution measured(long id) {
		return Execution.builder().id(id).startedAt(LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0, 0))
				.finishedAt(LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0, 10)).filesFound(100).build();
	}

	private ExecutionMetricsContext measuring() {
		ExecutionMetricsContext context = new ExecutionMetricsContext();

		context.processing().incExecuted();
		context.processing().incCacheAvoided(4);
		context.processing().recordGateWait(ExternalToolCategory.FFMPEG_PHOTO_HASH, 2_000_000L);
		context.processing().recordExternalExec(ExternalToolCategory.FFMPEG_PHOTO_HASH, 8_000_000L);
		context.phases().addNanos(ExecutionPhaseType.EXTRACTION, 3_000_000L);
		context.phases().addItems(ExecutionPhaseType.EXTRACTION, 7);

		return context;
	}

	@Test
	void recordsTheAggregateWithTheAttemptThatMeasuredIt() {
		when(executionRepository.findById(7L)).thenReturn(Optional.of(measured(7L)));
		when(executionMetricsRepository.findById(7L)).thenReturn(Optional.empty());

		service("9.9.9.9", ZoneId.of("UTC")).recordMetrics(current(7L, 3), measuring(),
				new ConfigSnapshot(4, 200, 2, 3));

		ExecutionMetrics metrics = savedMetrics();

		Assertions.assertThat(metrics.getAttemptClaimCount()).isEqualTo(3);
		Assertions.assertThat(metrics.getDurationMillis()).isEqualTo(10_000L);
		Assertions.assertThat(metrics.getFilesPerSecond()).isEqualTo(10.0);
		Assertions.assertThat(metrics.getTasksExecuted()).isEqualTo(1);
		Assertions.assertThat(metrics.getTasksCacheAvoided()).isEqualTo(4);
		Assertions.assertThat(metrics.getWorkers()).isEqualTo(4);
	}

	/** Nanos in memory, millis in the row - the same unit the phases use. */
	@Test
	void storesTheAccumulatedWaitsInMillis() {
		when(executionRepository.findById(7L)).thenReturn(Optional.of(measured(7L)));
		when(executionMetricsRepository.findById(7L)).thenReturn(Optional.empty());

		ExecutionMetricsContext context = new ExecutionMetricsContext();

		context.processing().recordQueueWait(1_500_000_000L);

		service("v", ZoneId.of("UTC")).recordMetrics(current(7L, 1), context, null);

		Assertions.assertThat(savedMetrics().getQueueWaitMillis()).isEqualTo(1_500L);
	}

	@Test
	void writesOnePhaseRowPerMeasuredPhase() {
		when(executionRepository.findById(7L)).thenReturn(Optional.of(measured(7L)));
		when(executionMetricsRepository.findById(7L)).thenReturn(Optional.empty());

		service("v", ZoneId.of("UTC")).recordMetrics(current(7L, 1), measuring(), null);

		ArgumentCaptor<List<ExecutionPhase>> phases = ArgumentCaptor.captor();

		verify(executionPhaseRepository).saveAll(phases.capture());

		Assertions.assertThat(phases.getValue()).singleElement()
				.satisfies(phase -> Assertions.assertThat(phase.getPhase()).isEqualTo(ExecutionPhaseType.EXTRACTION));
	}

	/**
	 * Only the tools the run actually reached. A category that was never used has
	 * no row, which is what separates "did not use it" from "used it for free".
	 */
	@Test
	void writesOneCategoryRowPerToolTheRunActuallyUsed() {
		when(executionRepository.findById(7L)).thenReturn(Optional.of(measured(7L)));
		when(executionMetricsRepository.findById(7L)).thenReturn(Optional.empty());

		service("v", ZoneId.of("UTC")).recordMetrics(current(7L, 1), measuring(), null);

		ArgumentCaptor<List<ExecutionMetricsCategory>> categories = ArgumentCaptor.captor();

		verify(executionMetricsCategoryRepository).saveAll(categories.capture());

		Assertions.assertThat(categories.getValue()).singleElement().satisfies(row -> {
			Assertions.assertThat(row.getCategory()).isEqualTo(ExternalToolCategory.FFMPEG_PHOTO_HASH);
			Assertions.assertThat(row.getRuns()).isEqualTo(1);
			Assertions.assertThat(row.getGateWaitMillis()).isEqualTo(2);
			Assertions.assertThat(row.getExternalExecMillis()).isEqualTo(8);
		});
	}

	/**
	 * Replacing, not appending: a reclaimed run measures from zero, and adding to
	 * what the previous attempt left would report a run twice as long as it was.
	 */
	@Test
	void clearsWhatAPreviousAttemptLeftBeforeWritingItsOwn() {
		when(executionRepository.findById(7L)).thenReturn(Optional.of(measured(7L)));
		when(executionMetricsRepository.findById(7L)).thenReturn(Optional.empty());

		service("v", ZoneId.of("UTC")).recordMetrics(current(7L, 2), measuring(), null);

		verify(executionPhaseRepository).deleteByExecutionId(7L);
		verify(executionMetricsCategoryRepository).deleteByExecutionId(7L);
	}

	/**
	 * The one that matters most. A superseded attempt must not delete the phases
	 * and categories of the taking that replaced it - not merely fail to add its
	 * own - so the fence comes before every write, including the deletes.
	 */
	@Test
	void aSupersededAttemptTouchesNothingAtAll() {
		boolean written = service("v", ZoneId.of("UTC")).recordMetrics(superseded(), measuring(), null);

		Assertions.assertThat(written).isFalse();

		verify(executionRepository, never()).findById(any());
		verify(executionMetricsRepository, never()).save(any());
		verify(executionPhaseRepository, never()).deleteByExecutionId(any());
		verify(executionPhaseRepository, never()).saveAll(any());
		verify(executionMetricsCategoryRepository, never()).deleteByExecutionId(any());
		verify(executionMetricsCategoryRepository, never()).saveAll(any());
	}

	@Test
	void ignoresAnExecutionThatIsNoLongerThere() {
		when(executionRepository.findById(9L)).thenReturn(Optional.empty());

		Assertions.assertThat(service("v", ZoneId.of("UTC")).recordMetrics(current(9L, 1), measuring(), null))
				.isFalse();

		verify(executionMetricsRepository, never()).save(any());
	}

	@Test
	void stampsTheApplicationVersionWhenTheRowHasNoneYet() {
		Execution execution = measured(11L);

		when(executionRepository.findById(11L)).thenReturn(Optional.of(execution));
		when(executionMetricsRepository.findById(11L)).thenReturn(Optional.empty());

		service("5.4.0.41", ZoneId.of("UTC")).recordMetrics(current(11L, 1), measuring(), null);

		Assertions.assertThat(execution.getApplicationVersion()).isEqualTo("5.4.0.41");
	}

	@Test
	void keepsTheVersionAnExecutionAlreadyCarries() {
		Execution execution = measured(12L);

		execution.setApplicationVersion("3.1.0.7");

		when(executionRepository.findById(12L)).thenReturn(Optional.of(execution));
		when(executionMetricsRepository.findById(12L)).thenReturn(Optional.empty());

		service("5.4.0.41", ZoneId.of("UTC")).recordMetrics(current(12L, 1), measuring(), null);

		Assertions.assertThat(execution.getApplicationVersion()).isEqualTo("3.1.0.7");
	}

	/**
	 * An execution that never finished has no elapsed time to report, and the row
	 * says so rather than inventing a zero that would read as instantaneous.
	 */
	@Test
	void leavesTheDurationUnsetWhenTheRunNeverFinished() {
		Execution unfinished = Execution.builder().id(13L)
				.startedAt(LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0, 0)).build();

		when(executionRepository.findById(13L)).thenReturn(Optional.of(unfinished));
		when(executionMetricsRepository.findById(13L)).thenReturn(Optional.empty());

		service("v", ZoneId.of("UTC")).recordMetrics(current(13L, 1), measuring(), null);

		Assertions.assertThat(savedMetrics().getDurationMillis()).isNull();
		Assertions.assertThat(savedMetrics().getFilesPerSecond()).isNull();
	}

	/**
	 * Organization reports what it moved rather than what it found, and the rate
	 * has to mean something for both.
	 */
	@Test
	void usesFilesMovedForTheRateWhenNothingWasFound() {
		Execution execution = Execution.builder().id(14L).startedAt(LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0, 0))
				.finishedAt(LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0, 10)).filesFound(0).filesMoved(200).build();

		when(executionRepository.findById(14L)).thenReturn(Optional.of(execution));
		when(executionMetricsRepository.findById(14L)).thenReturn(Optional.empty());

		service("v", ZoneId.of("UTC")).recordMetrics(current(14L, 1), measuring(), null);

		Assertions.assertThat(savedMetrics().getFilesPerSecond()).isEqualTo(20.0);
	}
}