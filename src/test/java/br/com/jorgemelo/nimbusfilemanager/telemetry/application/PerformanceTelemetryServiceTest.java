package br.com.jorgemelo.nimbusfilemanager.telemetry.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhaseType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionPhaseRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.ConfigSnapshot;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.PhaseSnapshot;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.PhotoHashCounters;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.model.ExecutionMetrics;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.repository.ExecutionMetricsRepository;

@ExtendWith(MockitoExtension.class)
class PerformanceTelemetryServiceTest {

	@Mock
	private ExecutionRepository executionRepository;

	@Mock
	private ExecutionMetricsRepository executionMetricsRepository;

	@Mock
	private ExecutionPhaseRepository executionPhaseRepository;

	private PerformanceTelemetryService service(String version, ZoneId zone) {
		return new PerformanceTelemetryService(executionRepository, executionMetricsRepository, executionPhaseRepository,
				version, Clock.system(zone));
	}

	private ExecutionMetrics savedMetrics() {
		ArgumentCaptor<ExecutionMetrics> captor = ArgumentCaptor.forClass(ExecutionMetrics.class);

		verify(executionMetricsRepository).save(captor.capture());

		return captor.getValue();
	}

	@Test
	void recordsDurationRateVersionConfigAndPhases() {
		Execution execution = Execution.builder().id(7L).startedAt(LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0, 0))
				.finishedAt(LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0, 10)).filesFound(100).build();

		when(executionRepository.findById(7L)).thenReturn(Optional.of(execution));
		when(executionMetricsRepository.findById(7L)).thenReturn(Optional.empty());

		service("9.9.9.9", ZoneId.of("UTC")).recordMetrics(7L, new ConfigSnapshot(4, 200, 2, 3),
				Map.of(ExecutionPhaseType.CACHE_CHECK, new PhaseSnapshot(50, 0), ExecutionPhaseType.EXTRACTION,
						new PhaseSnapshot(8000, 100)));

		ExecutionMetrics metrics = savedMetrics();

		Assertions.assertThat(metrics.getExecution()).isSameAs(execution);
		Assertions.assertThat(metrics.getDurationMillis()).isEqualTo(10_000);
		Assertions.assertThat(metrics.getFilesPerSecond()).isEqualTo(10.0);
		Assertions.assertThat(metrics.getWorkers()).isEqualTo(4);
		Assertions.assertThat(metrics.getChunkSize()).isEqualTo(200);
		Assertions.assertThat(metrics.getFfmpegPhotoHashLimit()).isEqualTo(2);
		Assertions.assertThat(metrics.getFfprobeVideoLimit()).isEqualTo(3);
		Assertions.assertThat(execution.getApplicationVersion()).isEqualTo("9.9.9.9");

		verify(executionRepository).save(execution);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ExecutionPhase>> captor = ArgumentCaptor.forClass(List.class);

		verify(executionPhaseRepository).saveAll(captor.capture());

		Assertions.assertThat(captor.getValue()).hasSize(2)
				.allSatisfy(phase -> Assertions.assertThat(phase.getExecutionId()).isEqualTo(7L));
	}

	@Test
	void recordsPhotoHashCountersWhenProvided() {
		Execution execution = Execution.builder().id(8L).startedAt(LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0, 0))
				.finishedAt(LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0, 5)).filesFound(50).build();

		when(executionRepository.findById(8L)).thenReturn(Optional.of(execution));
		when(executionMetricsRepository.findById(8L)).thenReturn(Optional.empty());

		service("v", ZoneId.of("UTC")).recordMetrics(8L, null, Map.of(), new PhotoHashCounters(17683, 84, 52));

		ExecutionMetrics metrics = savedMetrics();

		Assertions.assertThat(metrics.getPhotoHashJvmDecodable()).isEqualTo(17683L);
		Assertions.assertThat(metrics.getPhotoHashFfmpegOnly()).isEqualTo(84L);
		Assertions.assertThat(metrics.getPhotoHashFailures()).isEqualTo(52L);
	}

	@Test
	void usesFilesMovedForOrganizationRateWhenNoFilesFound() {
		Execution execution = Execution.builder().id(11L).startedAt(LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0, 0))
				.finishedAt(LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0, 10)).filesFound(0).filesMoved(200).build();

		when(executionRepository.findById(11L)).thenReturn(Optional.of(execution));
		when(executionMetricsRepository.findById(11L)).thenReturn(Optional.empty());

		service("v", ZoneId.of("UTC")).recordMetrics(11L, null, Map.of());

		Assertions.assertThat(savedMetrics().getFilesPerSecond()).isEqualTo(20.0);
	}

	@Test
	void zeroDurationYieldsZeroFilesPerSecondInsteadOfDividingByZero() {
		// A start == finish execution has zero duration; files/s must be 0, not Infinity/NaN.
		Execution execution = Execution.builder().id(12L).startedAt(LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0, 0))
				.finishedAt(LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0, 0)).filesFound(100).build();

		when(executionRepository.findById(12L)).thenReturn(Optional.of(execution));
		when(executionMetricsRepository.findById(12L)).thenReturn(Optional.empty());

		service("v", ZoneId.of("UTC")).recordMetrics(12L, null, Map.of());

		ExecutionMetrics metrics = savedMetrics();

		Assertions.assertThat(metrics.getDurationMillis()).isZero();
		Assertions.assertThat(metrics.getFilesPerSecond()).isZero();
	}

	@Test
	void computesTrueElapsedAcrossADaylightSavingSpringForward() {
		// America/New_York springs forward on 2024-03-10 at 02:00 -> 03:00 (offset -05:00 ->
		// -04:00). The stored LocalDateTimes are 90 wall-clock minutes apart, but only 30
		// minutes of real time elapsed; the zone-aware conversion measures the real elapsed.
		Execution execution = Execution.builder().id(21L)
				.startedAt(LocalDateTime.of(2024, Month.MARCH, 10, 1, 45, 0))
				.finishedAt(LocalDateTime.of(2024, Month.MARCH, 10, 3, 15, 0)).filesFound(10).build();

		when(executionRepository.findById(21L)).thenReturn(Optional.of(execution));
		when(executionMetricsRepository.findById(21L)).thenReturn(Optional.empty());

		service("v", ZoneId.of("America/New_York")).recordMetrics(21L, null, Map.of());

		// 01:45 EST = 06:45 UTC; 03:15 EDT = 07:15 UTC -> 30 min of real elapsed time. The
		// zone-less subtraction would instead have reported the 90-minute wall-clock delta.
		Assertions.assertThat(savedMetrics().getDurationMillis()).isEqualTo(1_800_000L);
	}

	@Test
	void keepsAnExistingApplicationVersionInsteadOfOverwriting() {
		Execution execution = Execution.builder().id(31L).applicationVersion("already-set")
				.startedAt(LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0, 0))
				.finishedAt(LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0, 1)).filesFound(1).build();

		when(executionRepository.findById(31L)).thenReturn(Optional.of(execution));
		when(executionMetricsRepository.findById(31L)).thenReturn(Optional.empty());

		service("service-version", ZoneId.of("UTC")).recordMetrics(31L, null, Map.of());

		Assertions.assertThat(execution.getApplicationVersion()).isEqualTo("already-set");
	}

	@Test
	void savesNoMetricsWhenThereIsNothingToRecord() {
		// No finishedAt (so no duration), no config, no counters: nothing to store, so no
		// execution_metrics row is created - only the execution's version is stamped.
		Execution execution = Execution.builder().id(40L)
				.startedAt(LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0, 0)).filesFound(5).build();

		when(executionRepository.findById(40L)).thenReturn(Optional.of(execution));

		service("v", ZoneId.of("UTC")).recordMetrics(40L, null, Map.of());

		verify(executionRepository).save(execution);
		verify(executionMetricsRepository, never()).save(any());
	}

	@Test
	void ignoresAMissingExecution() {
		when(executionRepository.findById(9L)).thenReturn(Optional.empty());

		service("v", ZoneId.of("UTC")).recordMetrics(9L, null, Map.of());

		verify(executionRepository, never()).save(any());
		verify(executionMetricsRepository, never()).save(any());
		verify(executionPhaseRepository, never()).saveAll(any());
	}
}
