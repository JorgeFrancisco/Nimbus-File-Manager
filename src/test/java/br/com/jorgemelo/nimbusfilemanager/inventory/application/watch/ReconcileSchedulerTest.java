package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ReconcileExecutionRecorder;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationReconcileService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileResponse;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Inventory;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;

class ReconcileSchedulerTest {

	@TempDir
	Path tempDir;

	private final AppSettingService appSettingService = mock(AppSettingService.class);
	private final OrganizationReconcileService organizationReconcileService = mock(OrganizationReconcileService.class);
	private final ReconcileExecutionRecorder reconcileExecutionRecorder = mock(ReconcileExecutionRecorder.class);

	private ReconcileScheduler scheduler;

	@AfterEach
	void tearDown() {
		if (scheduler != null) {
			scheduler.shutdown();
		}
	}

	@Test
	void runOnceReconcilesTheConfiguredFolderAndRecordsWithTimerTrigger() {
		when(appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn(tempDir.toString());
		when(appSettingService.booleanValue(SettingsConstants.WATCH_RECURSIVE, true)).thenReturn(true);
		when(appSettingService.booleanValue(SettingsConstants.WATCH_INCLUDE_HIDDEN, false)).thenReturn(false);

		OrganizationReconcileResponse response = response();

		when(organizationReconcileService.reconcileAndApply(any())).thenReturn(response);

		scheduler = scheduler();
		scheduler.runOnce();

		verify(organizationReconcileService).reconcileAndApply(any());
		verify(reconcileExecutionRecorder).recordIfRepaired(ExecutionTrigger.TIMER,
				tempDir.toAbsolutePath().normalize(), response);
	}

	/**
	 * A background repair pass must never take the application down with it: an
	 * unexpected failure is logged and the timer keeps its schedule.
	 */
	@Test
	void runOnceSwallowsAnUnexpectedFailureSoTheTimerSurvives() throws Exception {
		Path folder = Files.createDirectories(tempDir.resolve("library"));

		when(appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn(folder.toString());
		when(organizationReconcileService.reconcileAndApply(ArgumentMatchers.any()))
				.thenThrow(new IllegalStateException("catalog unreachable"));

		scheduler = scheduler();

		Assertions.assertThatCode(() -> scheduler.runOnce()).doesNotThrowAnyException();

		verifyNoInteractions(reconcileExecutionRecorder);
	}

	/**
	 * The same failure during shutdown is expected, not news: the pass is being
	 * interrupted on purpose, and a stack trace at ERROR would be noise.
	 */
	@Test
	void runOnceStaysQuietWhenTheFailureComesFromShuttingDown() throws Exception {
		Path folder = Files.createDirectories(tempDir.resolve("library"));

		when(appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn(folder.toString());
		when(organizationReconcileService.reconcileAndApply(ArgumentMatchers.any()))
				.thenThrow(new IllegalStateException("interrupted"));

		scheduler = scheduler();
		scheduler.shutdown();

		Assertions.assertThatCode(() -> scheduler.runOnce()).doesNotThrowAnyException();
	}

	@Test
	void runOnceDoesNothingWhenNoFolderIsConfigured() {
		when(appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn("");

		scheduler = scheduler();
		scheduler.runOnce();

		verifyNoInteractions(organizationReconcileService);
		verifyNoInteractions(reconcileExecutionRecorder);
	}

	@Test
	void runOnceDoesNothingAndDoesNotThrowWhenTheConfiguredFolderDoesNotExist() {
		when(appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, ""))
				.thenReturn(tempDir.resolve("does-not-exist").toString());

		scheduler = scheduler();
		scheduler.runOnce();

		verifyNoInteractions(organizationReconcileService);
		verifyNoInteractions(reconcileExecutionRecorder);
	}

	private ReconcileScheduler scheduler() {
		return new ReconcileScheduler(appSettingService, organizationReconcileService, reconcileExecutionRecorder,
				properties());
	}

	private NimbusFileManagerProperties properties() {
		return new NimbusFileManagerProperties(null, null, new Inventory(false, 60_000L), null, null, null);
	}

	private OrganizationReconcileResponse response() {
		return new OrganizationReconcileResponse(tempDir.toString(), true, false, 0, 0, 0, 0, 0, List.of(), List.of(),
				List.of(), 1, 0, 0);
	}
}