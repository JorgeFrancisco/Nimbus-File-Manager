package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;

import br.com.jorgemelo.nimbusfilemanager.backup.application.RestoreNotice;
import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.CatalogRestored;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.SizeResponse;
import br.com.jorgemelo.nimbusfilemanager.statistics.application.StatisticsService;
import br.com.jorgemelo.nimbusfilemanager.statistics.application.dto.StatisticsSummaryResponse;

class DashboardWebControllerTest {

	private static final LocalDateTime NOW = LocalDateTime.parse("2026-07-08T12:00:00");

	@Test
	void dashboardShouldLoadSummaryAndExecutionsWhenWatchFolderIsConfigured() {
		ExecutionQueryService executionQueryService = mock(ExecutionQueryService.class);
		StatisticsService statisticsService = mock(StatisticsService.class);
		AppSettingService appSettingService = mock(AppSettingService.class);
		ExtendedModelMap model = new ExtendedModelMap();
		StatisticsSummaryResponse summary = summary();
		ExecutionResponse execution = execution();
		var executionsPage = new PageImpl<>(List.of(execution), PageRequest.of(0, 20), 1);

		when(appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn("C:/media");
		when(statisticsService.summary()).thenReturn(summary);
		when(executionQueryService.page(0, 20)).thenReturn(executionsPage);

		String view = new DashboardWebController(executionQueryService, statisticsService, appSettingService,
				new RestoreNotice())
				.dashboard(model);

		Assertions.assertThat(view).isEqualTo("app/dashboard");
		Assertions.assertThat(model).containsEntry("summary", summary).containsEntry("executionsPage", executionsPage)
				.containsEntry("hasRunningExecutions", false);
	}

	@Test
	void executionItemsShouldReturnNextPageAsFragmentWithHasNextHeader() {
		ExecutionQueryService executionQueryService = mock(ExecutionQueryService.class);
		StatisticsService statisticsService = mock(StatisticsService.class);
		AppSettingService appSettingService = mock(AppSettingService.class);
		ExtendedModelMap model = new ExtendedModelMap();
		ExecutionResponse execution = execution();
		var executionsPage = new PageImpl<>(List.of(execution), PageRequest.of(1, 20), 21);
		var response = new MockHttpServletResponse();

		when(executionQueryService.page(1, 20)).thenReturn(executionsPage);

		String view = new DashboardWebController(executionQueryService, statisticsService, appSettingService,
				new RestoreNotice())
				.executionItems(1, response, model);

		Assertions.assertThat(view).isEqualTo("app/dashboard :: rows");
		Assertions.assertThat(model).containsEntry("executionsPage", executionsPage);
		Assertions.assertThat(response.getHeader("X-Has-Next")).isEqualTo("false");
	}

	@Test
	void dashboardShouldRedirectToOnboardingWhenWatchFolderIsNotConfigured() {
		ExecutionQueryService executionQueryService = mock(ExecutionQueryService.class);
		StatisticsService statisticsService = mock(StatisticsService.class);
		AppSettingService appSettingService = mock(AppSettingService.class);
		ExtendedModelMap model = new ExtendedModelMap();

		when(appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn("");

		String view = new DashboardWebController(executionQueryService, statisticsService, appSettingService,
				new RestoreNotice())
				.dashboard(model);

		Assertions.assertThat(view).isEqualTo("redirect:/app/onboarding");
		Assertions.assertThat(model.get("summary")).isNull();
	}

	private StatisticsSummaryResponse summary() {
		return new StatisticsSummaryResponse(1, 1, 0, 0, 0, 0, 0, SizeResponse.of(10), SizeResponse.of(10),
				SizeResponse.of(0), SizeResponse.of(0), SizeResponse.of(0), SizeResponse.of(0));
	}

	private ExecutionResponse execution() {
		return new ExecutionResponse(1L, "INVENTORY", "FINISHED", NOW, NOW, "C:/media/input", null, 1, 1, 0, 0, 0, 0,
				null, null, "ok", false);
	}
	/**
	 * Where a restore lands: the welcome wizard sends the user to the dashboard the
	 * moment the restored settings name a library, so this is the first screen
	 * drawn afterwards - and the only one guaranteed to be drawn at all.
	 */
	@Test
	void tellsTheUserOnceThatTheCatalogCameFromABackup() {
		ExecutionQueryService executionQueryService = mock(ExecutionQueryService.class);
		StatisticsService statisticsService = mock(StatisticsService.class);
		AppSettingService appSettingService = mock(AppSettingService.class);

		when(appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn("C:/media");
		when(statisticsService.summary()).thenReturn(summary());
		when(executionQueryService.page(0, 20)).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

		RestoreNotice notice = new RestoreNotice();

		notice.onCatalogRestored(new CatalogRestored("nimbus-catalog-20260801-060000.zip"));

		DashboardWebController controller = new DashboardWebController(executionQueryService, statisticsService,
				appSettingService, notice);

		ExtendedModelMap first = new ExtendedModelMap();

		controller.dashboard(first);

		ExtendedModelMap second = new ExtendedModelMap();

		controller.dashboard(second);

		Assertions.assertThat(first).containsEntry("catalogRestored", "nimbus-catalog-20260801-060000.zip");
		Assertions.assertThat(second).containsEntry("catalogRestored", null);
	}

}