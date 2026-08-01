package br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.ui.ExtendedModelMap;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.batch.InventoryBatchLauncherService;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.InventoryWatchService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;

class OnboardingWebControllerTest {

	private static final LocalDateTime NOW = LocalDateTime.parse("2026-07-08T12:00:00");

	@TempDir
	private Path tempDir;

	@Test
	void onboardingShouldShowWizardWhenNotConfigured() {
		AppSettingService appSettingService = mock(AppSettingService.class);
		InventoryBatchLauncherService inventoryBatchLauncherService = mock(InventoryBatchLauncherService.class);
		InventoryWatchService inventoryWatchService = mock(InventoryWatchService.class);

		when(appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn("");

		String view = new OnboardingWebController(appSettingService, inventoryBatchLauncherService,
				inventoryWatchService).onboarding();

		Assertions.assertThat(view).isEqualTo("app/onboarding");
	}

	@Test
	void onboardingShouldRedirectToDashboardWhenAlreadyConfigured() {
		AppSettingService appSettingService = mock(AppSettingService.class);
		InventoryBatchLauncherService inventoryBatchLauncherService = mock(InventoryBatchLauncherService.class);
		InventoryWatchService inventoryWatchService = mock(InventoryWatchService.class);

		when(appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn("C:/media");

		String view = new OnboardingWebController(appSettingService, inventoryBatchLauncherService,
				inventoryWatchService).onboarding();

		Assertions.assertThat(view).isEqualTo("redirect:/app");
	}

	@Test
	void onboardingConfigureShouldSaveSettingsStartInventoryAndRedirectToProgress() throws Exception {
		AppSettingService appSettingService = mock(AppSettingService.class);
		InventoryBatchLauncherService inventoryBatchLauncherService = mock(InventoryBatchLauncherService.class);
		InventoryWatchService inventoryWatchService = mock(InventoryWatchService.class);
		ExtendedModelMap model = new ExtendedModelMap();
		ExecutionResponse execution = execution();
		Path source = Files.createDirectories(tempDir.resolve("onboarding"));
		TestingAuthenticationToken authentication = new TestingAuthenticationToken("admin@example.com", "password");

		when(inventoryBatchLauncherService.launch(any(), any())).thenReturn(execution);

		String view = new OnboardingWebController(appSettingService, inventoryBatchLauncherService,
				inventoryWatchService).configure(source.toString(), true, false, true, true, authentication, model);

		Assertions.assertThat(view).isEqualTo("redirect:/app/progress/" + execution.executionId() + "?kind=inventory");
		verify(appSettingService).update(SettingsConstants.WATCH_RECURSIVE, "true", "admin@example.com");
		verify(appSettingService).update(SettingsConstants.WATCH_FOLDER, source.toString(), "admin@example.com");
		verify(inventoryBatchLauncherService).launch(any(), any());
	}

	@Test
	void onboardingConfigureShouldShowErrorWhenSourcePathIsBlank() {
		AppSettingService appSettingService = mock(AppSettingService.class);
		InventoryBatchLauncherService inventoryBatchLauncherService = mock(InventoryBatchLauncherService.class);
		InventoryWatchService inventoryWatchService = mock(InventoryWatchService.class);
		ExtendedModelMap model = new ExtendedModelMap();

		String view = new OnboardingWebController(appSettingService, inventoryBatchLauncherService,
				inventoryWatchService).configure(" ", true, false, true, true, null, model);

		Assertions.assertThat(view).isEqualTo("app/onboarding");
		Assertions.assertThat(model).containsEntry("error", "Informe a pasta que deseja monitorar.");
		Mockito.verifyNoInteractions(inventoryBatchLauncherService);
		verify(appSettingService, never()).update(any(), any(), any());
	}

	@Test
	void onboardingConfigureShouldShowErrorWhenSourcePathDoesNotExist() {
		AppSettingService appSettingService = mock(AppSettingService.class);
		InventoryBatchLauncherService inventoryBatchLauncherService = mock(InventoryBatchLauncherService.class);
		InventoryWatchService inventoryWatchService = mock(InventoryWatchService.class);
		ExtendedModelMap model = new ExtendedModelMap();

		String view = new OnboardingWebController(appSettingService, inventoryBatchLauncherService,
				inventoryWatchService).configure(tempDir.resolve("does-not-exist").toString(), true, false, true, true,
						null, model);

		Assertions.assertThat(view).isEqualTo("app/onboarding");
		Mockito.verifyNoInteractions(inventoryBatchLauncherService);
	}

	private ExecutionResponse execution() {
		return new ExecutionResponse(1L, "INVENTORY", "FINISHED", NOW, NOW, "C:/media/input", null, 1, 1, 0, 0, 0, 0,
				null, null, "ok", false);
	}
}