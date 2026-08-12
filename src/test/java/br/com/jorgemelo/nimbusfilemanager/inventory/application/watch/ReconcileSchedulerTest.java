package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationPathKey;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Inventory;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;

class ReconcileSchedulerTest {

	@TempDir
	Path tempDir;

	private final AppSettingService appSettingService = mock(AppSettingService.class);
	private final ExecutionEnqueueService executionEnqueueService = mock(ExecutionEnqueueService.class);

	private ReconcileScheduler scheduler;

	@AfterEach
	void tearDown() {
		if (scheduler != null) {
			scheduler.shutdown();
		}
	}

	@Test
	void runOnceQueuesTheReconcileInsteadOfRunningIt() {
		when(appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn(tempDir.toString());
		when(appSettingService.booleanValue(SettingsConstants.WATCH_RECURSIVE, true)).thenReturn(true);

		scheduler = scheduler();
		scheduler.runOnce();

		ArgumentCaptor<Execution> queued = ArgumentCaptor.captor();

		// Through the periodic door, and the distinction is the whole point: the
		// ordinary one admits a successor beside a run, which for a tick that fires
		// faster than the pass takes means one always waiting and a queue that never
		// drains. Verified against the exact method so a change back is a red test.
		verify(executionEnqueueService).enqueueUnlessAlreadyActive(queued.capture());
		verify(executionEnqueueService, never()).enqueue(any());

		Assertions.assertThat(queued.getValue().getExecutionType()).isEqualTo(ExecutionType.RECONCILE);
		Assertions.assertThat(queued.getValue().getTriggerEvent()).isEqualTo(ExecutionTrigger.TIMER);
		Assertions.assertThat(queued.getValue().getSourcePath())
				.isEqualTo(tempDir.toAbsolutePath().normalize().toString());
		Assertions.assertThat(queued.getValue().getDedupKey()).isEqualTo(OperationPathKey.canonical(tempDir));
		Assertions.assertThat(queued.getValue().getRecursive()).isTrue();
	}

	/**
	 * The recursive setting travels with the request: a library configured not to
	 * recurse must not be walked in full by the pass that reconciles it.
	 */
	@Test
	void carriesTheConfiguredRecursionIntoTheQueuedRequest() {
		when(appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn(tempDir.toString());
		when(appSettingService.booleanValue(SettingsConstants.WATCH_RECURSIVE, true)).thenReturn(false);

		scheduler = scheduler();
		scheduler.runOnce();

		ArgumentCaptor<Execution> queued = ArgumentCaptor.captor();

		verify(executionEnqueueService).enqueueUnlessAlreadyActive(queued.capture());

		Assertions.assertThat(queued.getValue().getRecursive()).isFalse();
	}

	/**
	 * A background repair pass must never take the application down with it: an
	 * unexpected failure is logged and the timer keeps its schedule.
	 */
	@Test
	void runOnceSwallowsAnUnexpectedFailureSoTheTimerSurvives() throws Exception {
		Path folder = Files.createDirectories(tempDir.resolve("library"));

		when(appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn(folder.toString());
		when(executionEnqueueService.enqueueUnlessAlreadyActive(any()))
				.thenThrow(new IllegalStateException("catalog unreachable"));

		scheduler = scheduler();

		Assertions.assertThatCode(() -> scheduler.runOnce()).doesNotThrowAnyException();
	}

	/**
	 * The same failure during shutdown is expected, not news: the pass is being
	 * interrupted on purpose, and a stack trace at ERROR would be noise.
	 */
	@Test
	void runOnceStaysQuietWhenTheFailureComesFromShuttingDown() throws Exception {
		Path folder = Files.createDirectories(tempDir.resolve("library"));

		when(appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn(folder.toString());
		when(executionEnqueueService.enqueueUnlessAlreadyActive(any()))
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

		verifyNoInteractions(executionEnqueueService);
	}

	@Test
	void runOnceDoesNothingAndDoesNotThrowWhenTheConfiguredFolderDoesNotExist() {
		when(appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, ""))
				.thenReturn(tempDir.resolve("does-not-exist").toString());

		scheduler = scheduler();
		scheduler.runOnce();

		verifyNoInteractions(executionEnqueueService);
	}

	private ReconcileScheduler scheduler() {
		return new ReconcileScheduler(appSettingService, executionEnqueueService, properties());
	}

	private NimbusFileManagerProperties properties() {
		return new NimbusFileManagerProperties(null, new Inventory(false, 60_000L), null, null, null);
	}

}