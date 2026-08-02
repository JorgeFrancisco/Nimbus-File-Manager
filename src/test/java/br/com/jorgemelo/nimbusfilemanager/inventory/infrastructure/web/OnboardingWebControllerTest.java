package br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import br.com.jorgemelo.nimbusfilemanager.backup.application.BackupFolderResolver;
import br.com.jorgemelo.nimbusfilemanager.backup.application.CatalogBackupAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.backup.application.CatalogBackupService;
import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.BackupFile;
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

		String view = controller(appSettingService, inventoryBatchLauncherService, inventoryWatchService)
				.onboarding(new ExtendedModelMap());

		Assertions.assertThat(view).isEqualTo("app/onboarding");
	}

	@Test
	void onboardingShouldRedirectToDashboardWhenAlreadyConfigured() {
		AppSettingService appSettingService = mock(AppSettingService.class);
		InventoryBatchLauncherService inventoryBatchLauncherService = mock(InventoryBatchLauncherService.class);
		InventoryWatchService inventoryWatchService = mock(InventoryWatchService.class);

		when(appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn("C:/media");

		String view = controller(appSettingService, inventoryBatchLauncherService, inventoryWatchService)
				.onboarding(new ExtendedModelMap());

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

		String view = controller(appSettingService, inventoryBatchLauncherService, inventoryWatchService)
				.configure(source.toString(), true, false, true, true, authentication, model);

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

		String view = controller(appSettingService, inventoryBatchLauncherService, inventoryWatchService)
				.configure(" ", true, false, true, true, null, model);

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

		String view = controller(appSettingService, inventoryBatchLauncherService, inventoryWatchService)
				.configure(tempDir.resolve("does-not-exist").toString(), true, false, true, true, null, model);

		Assertions.assertThat(view).isEqualTo("app/onboarding");
		Mockito.verifyNoInteractions(inventoryBatchLauncherService);
	}

	/**
	 * A fresh installation is not always a fresh library. Someone arriving with a
	 * backup has to be able to restore it from here, because the folder to watch is
	 * inside the backup - requiring one first would mean inventorying a library to
	 * be allowed to replace it.
	 */
	@Test
	void onboardingShouldOfferTheBackupsItFoundSoARestoreCanConfigureTheInstallation() {
		AppSettingService appSettingService = mock(AppSettingService.class);
		CatalogBackupService catalogBackupService = mock(CatalogBackupService.class);
		BackupFolderResolver backupFolderResolver = mock(BackupFolderResolver.class);
		CatalogBackupAsyncRunner backupRunner = mock(CatalogBackupAsyncRunner.class);

		BackupFile backup = new BackupFile("nimbus-catalog-20260802-060000.zip", 2048, NOW);

		when(appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn("");
		when(catalogBackupService.list()).thenReturn(List.of(backup));
		when(backupFolderResolver.folder()).thenReturn(tempDir);

		ExtendedModelMap model = new ExtendedModelMap();

		String view = new OnboardingWebController(appSettingService, mock(InventoryBatchLauncherService.class),
				mock(InventoryWatchService.class), catalogBackupService, backupRunner, backupFolderResolver)
						.onboarding(model);

		Assertions.assertThat(view).isEqualTo("app/onboarding");
		Assertions.assertThat(model.getAttribute("backups")).isEqualTo(List.of(backup));
		Assertions.assertThat(model.getAttribute("backupFolder")).isEqualTo(tempDir.toString());
		Assertions.assertThat(model.getAttribute("restoring")).isEqualTo(false);
	}

	/**
	 * The backup is wherever the person kept it - an external drive, a network
	 * share - so the folder is asked for rather than assumed, and kept as the same
	 * setting the settings screen edits.
	 */
	@Test
	void choosingTheBackupFolderShouldSaveItAsTheInstallationSetting() throws Exception {
		AppSettingService appSettingService = mock(AppSettingService.class);
		Path folder = Files.createDirectories(tempDir.resolve("external-drive"));
		TestingAuthenticationToken authentication = new TestingAuthenticationToken("admin@example.com", "password");
		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String view = controller(appSettingService, mock(InventoryBatchLauncherService.class),
				mock(InventoryWatchService.class)).chooseBackupFolder(folder.toString(), authentication,
						redirectAttributes);

		Assertions.assertThat(view).isEqualTo("redirect:/app/onboarding");

		verify(appSettingService).update(SettingsConstants.BACKUP_FOLDER, folder.toString(), "admin@example.com");
	}

	/** A folder that is not there says so instead of being saved. */
	@Test
	void choosingAFolderThatDoesNotExistShouldSaySoAndSaveNothing() {
		AppSettingService appSettingService = mock(AppSettingService.class);
		RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

		String view = controller(appSettingService, mock(InventoryBatchLauncherService.class),
				mock(InventoryWatchService.class)).chooseBackupFolder(tempDir.resolve("gone").toString(), null,
						redirectAttributes);

		Assertions.assertThat(view).isEqualTo("redirect:/app/onboarding");
		Assertions.assertThat(redirectAttributes.getFlashAttributes()).containsKey("error");

		verify(appSettingService, never()).update(any(), any(), any());
	}

	/**
	 * The restore panel needs the backup collaborators; every case here is about
	 * the wizard, so they answer nothing by default.
	 */
	private OnboardingWebController controller(AppSettingService appSettingService,
			InventoryBatchLauncherService inventoryBatchLauncherService, InventoryWatchService inventoryWatchService) {
		CatalogBackupService catalogBackupService = mock(CatalogBackupService.class);
		BackupFolderResolver backupFolderResolver = mock(BackupFolderResolver.class);

		when(catalogBackupService.list()).thenReturn(List.of());
		when(backupFolderResolver.folder()).thenReturn(tempDir);

		return new OnboardingWebController(appSettingService, inventoryBatchLauncherService, inventoryWatchService,
				catalogBackupService, mock(CatalogBackupAsyncRunner.class), backupFolderResolver);
	}

	private ExecutionResponse execution() {
		return new ExecutionResponse(1L, "INVENTORY", "FINISHED", NOW, NOW, "C:/media/input", null, 1, 1, 0, 0, 0, 0,
				null, null, "ok", false);
	}
}