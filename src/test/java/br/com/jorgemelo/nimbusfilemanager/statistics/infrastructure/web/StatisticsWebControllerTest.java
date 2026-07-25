package br.com.jorgemelo.nimbusfilemanager.statistics.infrastructure.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;

import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.ExecutionLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhaseType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.ExecutionTelemetryRow;
import br.com.jorgemelo.nimbusfilemanager.statistics.application.StatisticsService;
import br.com.jorgemelo.nimbusfilemanager.statistics.application.constants.StatisticsConstants;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionTelemetryQueryService;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.dto.ExecutionComparison;

@ExtendWith(MockitoExtension.class)
class StatisticsWebControllerTest {

	@Mock
	private StatisticsService statisticsService;

	@Mock
	private ExecutionTelemetryQueryService executionTelemetryQueryService;

	@Mock
	private UserPagePreferenceService userPagePreferenceService;

	private StatisticsWebController controller() {
		return new StatisticsWebController(statisticsService, executionTelemetryQueryService,
				userPagePreferenceService, new ExecutionLabels());
	}

	@Test
	void statisticsPopulatesLibraryAndTelemetryModel() {
		ExecutionTelemetryRow latest = row(5L);
		ExecutionTelemetryRow previous = row(4L);

		when(executionTelemetryQueryService.recent(null)).thenReturn(List.of(latest, previous));
		when(executionTelemetryQueryService.phases(5L)).thenReturn(List
				.of(phase(5L, ExecutionPhaseType.EXTRACTION, 800L), phase(5L, ExecutionPhaseType.PERSISTENCE, 200L)));
		when(executionTelemetryQueryService.versions()).thenReturn(List.of("3.3.0.13"));

		ExtendedModelMap model = new ExtendedModelMap();

		String view = controller().statistics(null, null, model);

		Assertions.assertThat(view).isEqualTo("app/statistics");
		Assertions.assertThat(model).containsEntry("versions", List.of("3.3.0.13"));
		Assertions.assertThat(model.containsAttribute("extensions")).isTrue();
		Assertions.assertThat(model.containsAttribute("codecs")).isTrue();
		Assertions.assertThat(model.containsAttribute("telemetry")).isTrue();
		// Performance mini-charts and the latest-execution phase breakdown.
		Assertions.assertThat(model).containsEntry("perfSeries", List.of(latest, previous))
				.containsEntry("maxFilesPerSecond", 10.0).containsEntry("maxDurationMillis", 1_000L)
				.containsEntry("latestExecution", latest).containsEntry("maxLatestPhaseMillis", 800L);
		Assertions.assertThat((List<?>) model.get("latestPhases")).hasSize(2);
		// Localized enum labels the telemetry views render instead of raw enum names.
		Assertions.assertThat(model.containsAttribute("executionTypeLabels")).isTrue();
		Assertions.assertThat(model.containsAttribute("executionStatusLabels")).isTrue();
		Assertions.assertThat(phaseLabels(model)).containsEntry(ExecutionPhaseType.EXTRACTION, "Extração de metadados")
				.containsEntry(ExecutionPhaseType.SCAN_COUNT, "Contagem de arquivos").hasSize(7);
	}

	@SuppressWarnings("unchecked")
	private Map<ExecutionPhaseType, String> phaseLabels(ExtendedModelMap model) {
		return (Map<ExecutionPhaseType, String>) model.get("phaseLabels");
	}

	@Test
	void statisticsLeavesChartsEmptyWhenNoTelemetry() {
		when(executionTelemetryQueryService.recent(null)).thenReturn(List.of());

		ExtendedModelMap model = new ExtendedModelMap();

		controller().statistics(null, null, model);

		Assertions.assertThat(model.get("latestExecution")).isNull();
		Assertions.assertThat((List<?>) model.get("perfSeries")).isEmpty();
		Assertions.assertThat((List<?>) model.get("latestPhases")).isEmpty();
		Assertions.assertThat(model).containsEntry("maxFilesPerSecond", 0.0).containsEntry("maxDurationMillis", 0L)
				.containsEntry("maxLatestPhaseMillis", 0L);
	}

	@Test
	void statisticsPersistsTheSubmittedVersionFilter() {
		when(executionTelemetryQueryService.recent("3.4.0.14")).thenReturn(List.of());

		ExtendedModelMap model = new ExtendedModelMap();

		controller().statistics("3.4.0.14", null, model);

		Assertions.assertThat(model).containsEntry("selectedVersion", "3.4.0.14");

		verify(userPagePreferenceService).save("system", StatisticsConstants.PAGE_KEY,
				StatisticsConstants.VERSION_KEY, "3.4.0.14");
	}

	@Test
	void statisticsAppliesTheSavedVersionWhenNoneIsRequested() {
		when(userPagePreferenceService.find("system", StatisticsConstants.PAGE_KEY))
				.thenReturn(Map.of(StatisticsConstants.VERSION_KEY, "3.4.0.14"));
		when(executionTelemetryQueryService.recent("3.4.0.14")).thenReturn(List.of());

		ExtendedModelMap model = new ExtendedModelMap();

		controller().statistics(null, null, model);

		Assertions.assertThat(model).containsEntry("selectedVersion", "3.4.0.14");
	}

	@Test
	void statisticsStoresTheAllChoiceAsBlankAndListsEveryVersion() {
		when(executionTelemetryQueryService.recent(null)).thenReturn(List.of());

		ExtendedModelMap model = new ExtendedModelMap();

		controller().statistics("", null, model);

		Assertions.assertThat(model.get("selectedVersion")).isNull();

		verify(userPagePreferenceService).save("system", StatisticsConstants.PAGE_KEY,
				StatisticsConstants.VERSION_KEY, "");
	}

	@Test
	void executionDetailExposesRowAndPhases() {
		ExecutionTelemetryRow row = new ExecutionTelemetryRow(7L, UUID.randomUUID(), ExecutionType.INVENTORY,
				ExecutionStatus.FINISHED, LocalDateTime.now(), LocalDateTime.now(), 1_000L, 10.0, 100, 0, "3.3.0.13", 3,
				200, 2, 2, 90L, 8L, 2L);

		when(executionTelemetryQueryService.byId(7L)).thenReturn(Optional.of(row));
		when(executionTelemetryQueryService.phases(7L)).thenReturn(List.of());

		ExtendedModelMap model = new ExtendedModelMap();

		String view = controller().executionTelemetry(7L, model);

		Assertions.assertThat(view).isEqualTo("app/statistics-execution");
		Assertions.assertThat(model).containsEntry("execution", row);
		Assertions.assertThat(model.containsAttribute("phases")).isTrue();
	}

	@Test
	void executionDetailRedirectsWhenExecutionIsMissing() {
		when(executionTelemetryQueryService.byId(9L)).thenReturn(Optional.empty());

		String view = controller().executionTelemetry(9L, new ExtendedModelMap());

		Assertions.assertThat(view).isEqualTo("redirect:/app/statistics");
	}

	@Test
	void compareRedirectsWhenFewerThanTwoIdsAreGiven() {
		Assertions.assertThat(controller().compare(null, new ExtendedModelMap())).isEqualTo("redirect:/app/statistics");
		Assertions.assertThat(controller().compare(List.of(1L), new ExtendedModelMap()))
				.isEqualTo("redirect:/app/statistics");
	}

	@Test
	void compareRedirectsWhenFewerThanTwoExecutionsResolve() {
		when(executionTelemetryQueryService.compare(List.of(1L, 2L)))
				.thenReturn(new ExecutionComparison(List.of(row(1L)), List.of(), 0));

		Assertions.assertThat(controller().compare(List.of(1L, 2L), new ExtendedModelMap()))
				.isEqualTo("redirect:/app/statistics");
	}

	@Test
	void compareExposesComparisonModel() {
		ExecutionComparison comparison = new ExecutionComparison(List.of(row(1L), row(2L)), List.of(), 0);

		when(executionTelemetryQueryService.compare(List.of(1L, 2L))).thenReturn(comparison);

		ExtendedModelMap model = new ExtendedModelMap();

		Assertions.assertThat(controller().compare(List.of(1L, 2L), model)).isEqualTo("app/statistics-compare");
		Assertions.assertThat(model).containsEntry("comparison", comparison);
	}

	private ExecutionTelemetryRow row(Long id) {
		return new ExecutionTelemetryRow(id, UUID.randomUUID(), ExecutionType.INVENTORY, ExecutionStatus.FINISHED,
				LocalDateTime.now(), LocalDateTime.now(), 1_000L, 10.0, 100, 0, "3.4.0.14", 3, 200, 2, 2, 90L, 8L, 2L);
	}

	private ExecutionPhase phase(Long executionId, ExecutionPhaseType type, long durationMillis) {
		return ExecutionPhase.builder().id(executionId).executionId(executionId).phase(type)
				.durationMillis(durationMillis).items(0L).build();
	}
}