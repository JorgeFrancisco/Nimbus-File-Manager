package br.com.jorgemelo.nimbusfilemanager.telemetry.application;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhaseType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.ExecutionTelemetryRow;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.ExecutionComparison;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.repository.ExecutionPhaseRepository;

@ExtendWith(MockitoExtension.class)
class ExecutionTelemetryQueryServiceCompareTest {

	@Mock
	private ExecutionRepository executionRepository;

	@Mock
	private ExecutionPhaseRepository executionPhaseRepository;

	private ExecutionTelemetryQueryService service() {
		return new ExecutionTelemetryQueryService(executionRepository, executionPhaseRepository);
	}

	@Test
	void compareAlignsPhaseDurationsToTheExecutionOrder() {
		// findTelemetryByIds returns most-recent-first: 2 then 1. That is the column
		// order.
		when(executionRepository.findTelemetryByIds(List.of(1L, 2L))).thenReturn(List.of(row(2L), row(1L)));
		when(executionPhaseRepository.findByExecutionIdInOrderByExecutionIdAscPhaseAsc(List.of(2L, 1L))).thenReturn(
				List.of(phase(2L, ExecutionPhaseType.EXTRACTION, 8000), phase(1L, ExecutionPhaseType.EXTRACTION, 5000),
						phase(1L, ExecutionPhaseType.PERSISTENCE, 1000)));

		ExecutionComparison comparison = service().compare(List.of(1L, 2L, 1L));

		Assertions.assertThat(comparison.executions()).extracting(ExecutionTelemetryRow::id).containsExactly(2L, 1L);
		Assertions.assertThat(comparison.maxPhaseMillis()).isEqualTo(8000);
		Assertions.assertThat(comparison.phases()).satisfiesExactly(extraction -> {
			Assertions.assertThat(extraction.phase()).isEqualTo(ExecutionPhaseType.EXTRACTION);
			Assertions.assertThat(extraction.durationsMillis()).containsExactly(8000L, 5000L);
		}, persistence -> {
			Assertions.assertThat(persistence.phase()).isEqualTo(ExecutionPhaseType.PERSISTENCE);
			Assertions.assertThat(persistence.durationsMillis()).containsExactly(0L, 1000L);
		});
	}

	@Test
	void compareReturnsEmptyForBlankOrUnknownIds() {
		Assertions.assertThat(service().compare(List.of()).isEmpty()).isTrue();

		when(executionRepository.findTelemetryByIds(anyList())).thenReturn(List.of());

		Assertions.assertThat(service().compare(List.of(99L)).isEmpty()).isTrue();
	}

	private ExecutionTelemetryRow row(Long id) {
		return new ExecutionTelemetryRow(id, UUID.randomUUID(), ExecutionType.INVENTORY, ExecutionStatus.FINISHED,
				LocalDateTime.now(), LocalDateTime.now(), 1_000L, 10.0, 100, 0, "3.4.0.14", 3, 200, 2, 2, 90L, 8L, 2L);
	}

	private ExecutionPhase phase(Long executionId, ExecutionPhaseType type, long millis) {
		return ExecutionPhase.builder().executionId(executionId).phase(type).durationMillis(millis).items(0L).build();
	}
}