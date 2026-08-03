package br.com.jorgemelo.nimbusfilemanager.telemetry.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.ExecutionTelemetryRow;
import br.com.jorgemelo.nimbusfilemanager.telemetry.domain.repository.ExecutionPhaseRepository;

/**
 * The read side of the telemetry screen. Comparison lives in its own test; this
 * one pins the plain queries, notably that a blank version filter means "no
 * filter" instead of "version is the empty string".
 */
class ExecutionTelemetryQueryServiceTest {

	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);
	private final ExecutionPhaseRepository executionPhaseRepository = mock(ExecutionPhaseRepository.class);
	private final ExecutionTelemetryQueryService service = new ExecutionTelemetryQueryService(executionRepository,
			executionPhaseRepository);

	@Test
	void recentShouldTreatABlankVersionAsNoFilterAndCapThePage() {
		service.recent("   ");

		verify(executionRepository).findTelemetry(null, PageRequest.of(0, 50));
	}

	@Test
	void recentShouldKeepAVersionFilterThatHasContent() {
		ExecutionTelemetryRow row = mock(ExecutionTelemetryRow.class);

		when(executionRepository.findTelemetry("5.4.0", PageRequest.of(0, 50))).thenReturn(List.of(row));

		Assertions.assertThat(service.recent(" 5.4.0 ")).containsExactly(row);
	}

	@Test
	void byIdShouldReturnWhatTheRepositoryFound() {
		ExecutionTelemetryRow row = mock(ExecutionTelemetryRow.class);

		when(executionRepository.findTelemetryById(7L)).thenReturn(Optional.of(row));

		Assertions.assertThat(service.byId(7L)).contains(row);
		Assertions.assertThat(service.byId(8L)).isEmpty();
	}

	@Test
	void phasesShouldReturnTheBreakdownInPhaseOrder() {
		ExecutionPhase phase = mock(ExecutionPhase.class);

		when(executionPhaseRepository.findByExecutionIdOrderByPhaseAsc(7L)).thenReturn(List.of(phase));

		Assertions.assertThat(service.phases(7L)).containsExactly(phase);
	}

	@Test
	void versionsShouldReturnTheDistinctMeasuredVersions() {
		when(executionRepository.findTelemetryVersions()).thenReturn(List.of("5.4.0", "5.3.9"));

		Assertions.assertThat(service.versions()).containsExactly("5.4.0", "5.3.9");
	}
}