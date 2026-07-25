package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.LoggerFactory;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ReconcileExecutionRecorder;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.batch.InventoryBatchLauncherService;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.FileChangeSourceFactory;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationReconcileService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileResponse;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.InventoryWatchProperties;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

// Manipulates the process-global logback logger of InventoryWatchService and is
// timing-sensitive (see the accepted Thread.sleep uses); runs alone so it is
// immune to interference from concurrently-scheduled test classes.
@Isolated
class InventoryWatchServiceTest {

	@TempDir
	Path tempDir;

	private InventoryWatchService service;

	@AfterEach
	void tearDown() {
		if (service != null) {
			service.stop();
		}
	}

	@Test
	void createdFileShouldTriggerDebouncedBatchForConfiguredFolder() throws Exception {
		AppSettingService settings = mock(AppSettingService.class);

		InventoryBatchLauncherService launcher = mock(InventoryBatchLauncherService.class);

		ExecutionQueryService queries = mock(ExecutionQueryService.class);

		OrganizationReconcileService reconcileService = mock(OrganizationReconcileService.class);

		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn(tempDir.toString());
		when(settings.booleanValue(SettingsConstants.WATCH_RECURSIVE, true)).thenReturn(true);
		when(settings.booleanValue(SettingsConstants.WATCH_INCLUDE_HIDDEN, false)).thenReturn(false);
		when(settings.booleanValue(SettingsConstants.WATCH_CALCULATE_HASHES, true)).thenReturn(true);
		when(settings.booleanValue(SettingsConstants.WATCH_FORCE_ANALYSIS, false)).thenReturn(false);
		when(queries.active()).thenReturn(Optional.empty());
		when(reconcileService.reconcileAndApply(any())).thenReturn(reconcile(0));

		service = new InventoryWatchService(settings, launcher, queries, reconcileService,
				mock(OperationLockService.class), watchOnlyFactory(), recorder(), Clock.systemDefaultZone(),
				watchProps(true));
		service.reconfigure();

		Files.writeString(tempDir.resolve("new-photo.jpg"), "test");

		verify(launcher, timeout(6_000)).launch(any(), any());
	}

	@Test
	void applicationStartupShouldInventoryFilesAlreadyInConfiguredFolder() throws Exception {
		Files.writeString(tempDir.resolve("already-there.txt"), "");

		AppSettingService settings = mock(AppSettingService.class);

		InventoryBatchLauncherService launcher = mock(InventoryBatchLauncherService.class);

		ExecutionQueryService queries = mock(ExecutionQueryService.class);

		OrganizationReconcileService reconcileService = mock(OrganizationReconcileService.class);

		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn(tempDir.toString());
		when(settings.booleanValue(SettingsConstants.WATCH_RECURSIVE, true)).thenReturn(true);
		when(settings.booleanValue(SettingsConstants.WATCH_INCLUDE_HIDDEN, false)).thenReturn(false);
		when(settings.booleanValue(SettingsConstants.WATCH_CALCULATE_HASHES, true)).thenReturn(true);
		when(settings.booleanValue(SettingsConstants.WATCH_FORCE_ANALYSIS, false)).thenReturn(false);

		service = new InventoryWatchService(settings, launcher, queries, reconcileService,
				mock(OperationLockService.class), watchOnlyFactory(), recorder(), Clock.systemDefaultZone(),
				watchProps(true));

		service.startConfiguredMonitor();

		verify(launcher).launch(any(), any());
	}

	@Test
	void monitorReconfigurationShouldImmediatelyInventoryTheNewFolder() {
		AppSettingService settings = mock(AppSettingService.class);

		InventoryBatchLauncherService launcher = mock(InventoryBatchLauncherService.class);

		ExecutionQueryService queries = mock(ExecutionQueryService.class);

		OrganizationReconcileService reconcileService = mock(OrganizationReconcileService.class);

		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn(tempDir.toString());
		when(queries.active()).thenReturn(Optional.empty());

		service = new InventoryWatchService(settings, launcher, queries, reconcileService,
				mock(OperationLockService.class), watchOnlyFactory(), recorder(), Clock.systemDefaultZone(),
				watchProps(true));
		service.reconfigureAndInventory();

		verify(launcher).launch(any(), any());

		assertThat(service.status().running()).isTrue();
	}

	@Test
	void applicationStartupShouldNotLaunchInventoryWithoutConfiguredFolder() {
		AppSettingService settings = mock(AppSettingService.class);

		InventoryBatchLauncherService launcher = mock(InventoryBatchLauncherService.class);

		ExecutionQueryService queries = mock(ExecutionQueryService.class);

		OrganizationReconcileService reconcileService = mock(OrganizationReconcileService.class);

		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn("");

		service = new InventoryWatchService(settings, launcher, queries, reconcileService,
				mock(OperationLockService.class), watchOnlyFactory(), recorder(), Clock.systemDefaultZone(),
				watchProps(true));

		service.startConfiguredMonitor();

		verify(launcher, never()).launch(any(), any());
	}

	@Test
	void deletedFileShouldTriggerAutomaticReconciliationAndBatch() throws Exception {
		Path existing = Files.writeString(tempDir.resolve("existing.jpg"), "test");

		AppSettingService settings = mock(AppSettingService.class);

		InventoryBatchLauncherService launcher = mock(InventoryBatchLauncherService.class);

		ExecutionQueryService queries = mock(ExecutionQueryService.class);

		OrganizationReconcileService reconcileService = mock(OrganizationReconcileService.class);

		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn(tempDir.toString());
		when(settings.booleanValue(SettingsConstants.WATCH_RECURSIVE, true)).thenReturn(true);
		when(settings.booleanValue(SettingsConstants.WATCH_INCLUDE_HIDDEN, false)).thenReturn(false);
		when(settings.booleanValue(SettingsConstants.WATCH_CALCULATE_HASHES, true)).thenReturn(true);
		when(settings.booleanValue(SettingsConstants.WATCH_FORCE_ANALYSIS, false)).thenReturn(false);
		when(queries.active()).thenReturn(Optional.empty());

		service = new InventoryWatchService(settings, launcher, queries, reconcileService,
				mock(OperationLockService.class), watchOnlyFactory(), recorder(), Clock.systemDefaultZone(),
				watchProps(true));

		when(reconcileService.reconcileAndApply(any())).thenReturn(new OrganizationReconcileResponse(tempDir.toString(),
				true, false, 0, 1, 1, 0, 0, List.of(), List.of(), List.of(), 0, 0, 0));

		service.reconfigure();

		Files.delete(existing);

		verify(reconcileService, timeout(6_000)).reconcileAndApply(any());
		verify(launcher, timeout(6_000)).launch(any(), any());
	}

	/**
	 * Regression test for the race condition where {@code reconfigure()} -
	 * called from the web request thread when an admin changes the monitored folder
	 * - could swap/close the {@code PhysicalTreeWatcher} while the scheduled poll
	 * thread was still mid-read on it. The fix makes the private {@code
	 * pollEvents()} synchronize on the same instance monitor as
	 * {@code reconfigure()}. This test proves that sharing directly: it holds the
	 * instance monitor from one thread and asserts that a concurrent call to
	 * {@code pollEvents()} (invoked via reflection, since it's private) blocks on
	 * it, then completes once the monitor is released - without depending on real
	 * filesystem watch timing, which would be flaky.
	 */
	@Test
	void pollEventsShouldBeMutuallyExclusiveWithReconfigure() throws Exception {
		AppSettingService settings = mock(AppSettingService.class);

		InventoryBatchLauncherService launcher = mock(InventoryBatchLauncherService.class);

		ExecutionQueryService queries = mock(ExecutionQueryService.class);

		OrganizationReconcileService reconcileService = mock(OrganizationReconcileService.class);

		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn("");

		service = new InventoryWatchService(settings, launcher, queries, reconcileService,
				mock(OperationLockService.class), watchOnlyFactory(), recorder(), Clock.systemDefaultZone(),
				watchProps(true));

		Method pollEvents = InventoryWatchService.class.getDeclaredMethod("pollEvents");

		pollEvents.setAccessible(true);

		CountDownLatch monitorHeld = new CountDownLatch(1);
		CountDownLatch releaseMonitor = new CountDownLatch(1);

		Thread holder = new Thread(() -> {
			synchronized (service) {
				monitorHeld.countDown();
				try {
					releaseMonitor.await(5, TimeUnit.SECONDS);
				} catch (InterruptedException _) {
					Thread.currentThread().interrupt();
				}
			}
		}, "monitor-holder");

		holder.setDaemon(true);

		holder.start();

		assertThat(monitorHeld.await(5, TimeUnit.SECONDS)).isTrue();

		Thread pollThread = new Thread(() -> {
			try {
				pollEvents.invoke(service);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		}, "poll-events-under-test");

		pollThread.setDaemon(true);

		pollThread.start();

		// While the monitor is held elsewhere, pollEvents() must be blocked trying to
		// acquire it -
		// proving it now shares the lock with reconfigure()/stopSource() instead of
		// running free.
		Thread.sleep(300);

		assertThat(pollThread.getState()).isEqualTo(Thread.State.BLOCKED);

		releaseMonitor.countDown();

		pollThread.join(5_000);

		holder.join(5_000);

		assertThat(pollThread.isAlive()).isFalse();
	}

	@Test
	void reconfigureWithBlankFolderLeavesMonitorUnconfigured() throws Exception {
		AppSettingService settings = mock(AppSettingService.class);

		InventoryBatchLauncherService launcher = mock(InventoryBatchLauncherService.class);

		ExecutionQueryService queries = mock(ExecutionQueryService.class);

		OrganizationReconcileService reconcileService = mock(OrganizationReconcileService.class);

		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn("");

		service = frozenService(settings, launcher, queries, reconcileService);
		service.reconfigure();

		assertThat(service.status().running()).isFalse();
		assertThat(service.status().configured()).isFalse();
	}

	@Test
	void reconfigureWithMissingFolderReportsError() throws Exception {
		AppSettingService settings = mock(AppSettingService.class);

		InventoryBatchLauncherService launcher = mock(InventoryBatchLauncherService.class);

		ExecutionQueryService queries = mock(ExecutionQueryService.class);

		OrganizationReconcileService reconcileService = mock(OrganizationReconcileService.class);

		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, ""))
				.thenReturn(tempDir.resolve("does-not-exist").toString());

		service = frozenService(settings, launcher, queries, reconcileService);

		service.reconfigure();

		assertThat(service.status().running()).isFalse();
		assertThat(service.status().configured()).isTrue();
		assertThat(service.status().error()).isNotBlank();
	}

	@Test
	void reconfigureAndInventoryQueuesInventoryWhenExecutionActive() throws Exception {
		AppSettingService settings = mock(AppSettingService.class);

		InventoryBatchLauncherService launcher = mock(InventoryBatchLauncherService.class);

		ExecutionQueryService queries = mock(ExecutionQueryService.class);

		OrganizationReconcileService reconcileService = mock(OrganizationReconcileService.class);

		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn(tempDir.toString());
		when(queries.active()).thenReturn(Optional.of(activeExecution()));

		service = frozenService(settings, launcher, queries, reconcileService);
		service.reconfigureAndInventory();

		// An inventory is already running, so the reconfiguration must queue the work
		// (inventoryPending) instead of launching a second batch immediately.
		verify(launcher, never()).launch(any(), any());

		assertThat(service.status().running()).isTrue();
		assertThat(booleanField("inventoryPending")).isTrue();
	}

	@Test
	void pauseStopsMonitoringAndClearsPending() throws Exception {
		service = frozenService(configuredSettings(), mock(InventoryBatchLauncherService.class),
				mock(ExecutionQueryService.class), mock(OrganizationReconcileService.class));

		service.reconfigure();

		assertThat(service.status().running()).isTrue();

		service.pause();

		assertThat(service.status().running()).isFalse();
		assertThat(service.status().error()).contains("Trocando");
		assertThat(booleanField("inventoryPending")).isFalse();
	}

	@Test
	void launchPendingInventorySkipsWhileWithinDebounceWindow() throws Exception {
		InventoryBatchLauncherService launcher = mock(InventoryBatchLauncherService.class);

		service = frozenService(configuredSettings(), launcher, mock(ExecutionQueryService.class),
				mock(OrganizationReconcileService.class));

		service.reconfigure();

		setField("inventoryPending", true);
		setField("lastEventMillis", System.currentTimeMillis());

		invokeBoolean("launchPendingInventory", false);

		verify(launcher, never()).launch(any(), any());
	}

	@Test
	void launchPendingInventoryLaunchesAfterDebounceElapses() throws Exception {
		InventoryBatchLauncherService launcher = mock(InventoryBatchLauncherService.class);

		OrganizationReconcileService reconcileService = mock(OrganizationReconcileService.class);

		when(reconcileService.reconcileAndApply(any())).thenReturn(reconcile(0));

		service = frozenService(configuredSettings(), launcher, mock(ExecutionQueryService.class), reconcileService);
		service.reconfigure();

		setField("inventoryPending", true);
		setField("lastEventMillis", System.currentTimeMillis() - 5_000L);

		invokeBoolean("launchPendingInventory", false);

		verify(reconcileService).reconcileAndApply(any());
		verify(launcher).launch(any(), any());

		assertThat(booleanField("inventoryPending")).isFalse();
	}

	@Test
	void launchPendingInventorySkipsWhenExecutionActive() throws Exception {
		InventoryBatchLauncherService launcher = mock(InventoryBatchLauncherService.class);

		service = frozenService(configuredSettings(), launcher, mock(ExecutionQueryService.class),
				mock(OrganizationReconcileService.class));

		service.reconfigure();

		setField("inventoryPending", true);
		setField("lastEventMillis", System.currentTimeMillis() - 5_000L);

		invokeBoolean("launchPendingInventory", true);

		verify(launcher, never()).launch(any(), any());
	}

	@Test
	void debouncedFileChangeReconcileUsesFileEventTriggerAndUpdatesHeartbeat() throws Exception {
		ReconcileExecutionRecorder recorder = recorder();

		OrganizationReconcileService reconcileService = mock(OrganizationReconcileService.class);

		when(reconcileService.reconcileAndApply(any())).thenReturn(repaired(1, 0, 0));

		service = frozenServiceWith(recorder, reconcileService);
		service.reconfigure();

		setField("inventoryPending", true);
		setField("lastEventMillis", System.currentTimeMillis() - 5_000L);

		invokeBoolean("launchPendingInventory", false);

		verify(recorder).recordIfRepaired(eq(ExecutionTrigger.FILE_EVENT), any(), any());

		assertThat(objectField("lastReconciliation")).isNotNull();
		assertThat(longField("lastReconciliationRepaired")).isEqualTo(1L);
	}

	@Test
	void overflowEventForcesAnEarlyReInventory() throws Exception {
		service = frozenService(configuredSettings(), mock(InventoryBatchLauncherService.class),
				mock(ExecutionQueryService.class), mock(OrganizationReconcileService.class));

		service.reconfigure();

		setField("inventoryPending", false);

		watcherField().handleEvent(tempDir, overflowEvent(), new ArrayList<>());

		invokeNoArgs("pollEvents");

		// A dropped-events overflow must schedule a debounced re-inventory (which runs
		// its own FILE_EVENT reconcile) instead of relying on the periodic scheduler.
		assertThat(booleanField("inventoryPending")).isTrue();
	}

	/**
	 * Once shutdown starts, a poll cycle must not begin: a fresh cycle would query
	 * the database (borrowing pooled connections the closing context is about to
	 * invalidate). Setting {@code shuttingDown} makes {@code pollSafely()} return
	 * before touching any collaborator.
	 */
	@Test
	void pollSafelySkipsAllWorkWhileShuttingDown() throws Exception {
		AppSettingService settings = mock(AppSettingService.class);

		InventoryBatchLauncherService launcher = mock(InventoryBatchLauncherService.class);

		ExecutionQueryService queries = mock(ExecutionQueryService.class);

		OrganizationReconcileService reconcileService = mock(OrganizationReconcileService.class);

		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn(tempDir.toString());

		service = frozenService(settings, launcher, queries, reconcileService);

		setField("shuttingDown", true);

		invokeNoArgs("pollSafely");

		verify(queries, never()).active();
		verify(launcher, never()).launch(any(), any());
	}

	/**
	 * {@code stop()} must drain the poll executor gracefully (letting an in-flight
	 * cycle finish, then terminating) rather than leaving a background thread that
	 * outlives the DataSource - the source of the Hikari "connection has been
	 * closed" teardown warnings.
	 */
	@Test
	void stopDrainsThePollExecutorGracefully() throws Exception {
		service = new InventoryWatchService(configuredSettings(), mock(InventoryBatchLauncherService.class),
				mock(ExecutionQueryService.class), mock(OrganizationReconcileService.class),
				mock(OperationLockService.class), watchOnlyFactory(), recorder(), Clock.systemDefaultZone(),
				watchProps(true));

		service.stop();

		assertThat(booleanField("shuttingDown")).isTrue();

		Field executorField = InventoryWatchService.class.getDeclaredField("executor");

		executorField.setAccessible(true);

		assertThat(((ExecutorService) executorField.get(service)).isTerminated()).isTrue();
	}

	@Test
	void stopCancelsTheScheduledPollTask() throws Exception {
		ExecutionQueryService queries = mock(ExecutionQueryService.class);

		when(queries.active()).thenReturn(Optional.empty());

		service = new InventoryWatchService(configuredSettings(), mock(InventoryBatchLauncherService.class), queries,
				mock(OrganizationReconcileService.class), mock(OperationLockService.class), watchOnlyFactory(), recorder(),
				Clock.systemDefaultZone(), watchProps(true));

		service.stop();

		Field pollTaskField = InventoryWatchService.class.getDeclaredField("pollTask");

		pollTaskField.setAccessible(true);

		ScheduledFuture<?> pollTask = (ScheduledFuture<?>) ((AtomicReference<?>) pollTaskField.get(service)).get();

		assertThat((Object) pollTask).isNotNull();
		assertThat(pollTask.isCancelled()).isTrue();
	}

	@Test
	void stopIsIdempotentAndSafeToCallTwice() throws Exception {
		service = new InventoryWatchService(configuredSettings(), mock(InventoryBatchLauncherService.class),
				mock(ExecutionQueryService.class), mock(OrganizationReconcileService.class),
				mock(OperationLockService.class), watchOnlyFactory(), recorder(), Clock.systemDefaultZone(),
				watchProps(false));

		service.stop();
		service.stop();

		assertThat(booleanField("shuttingDown")).isTrue();
	}

	@Test
	void pollSafelyDoesNothingAfterStop() throws Exception {
		ExecutionQueryService queries = mock(ExecutionQueryService.class);

		InventoryBatchLauncherService launcher = mock(InventoryBatchLauncherService.class);

		service = new InventoryWatchService(configuredSettings(), launcher, queries,
				mock(OrganizationReconcileService.class), mock(OperationLockService.class), watchOnlyFactory(), recorder(),
				Clock.systemDefaultZone(), watchProps(false));

		service.stop();

		invokeNoArgs("pollSafely");

		verify(queries, never()).active();
		verify(launcher, never()).launch(any(), any());
	}

	@Test
	void pollFailuresDuringShutdownAreLoggedAtDebugNotError() throws Exception {
		ExecutionQueryService queries = mock(ExecutionQueryService.class);

		when(queries.active()).thenThrow(new IllegalStateException("This connection has been closed."));

		// No reconfigure(): a null watcher makes pollEvents() a no-op, so the current
		// thread's interrupt flag survives to the active() call - the same way a
		// cancel(true)'d poll thread carries its interrupt into a shutdown-time query.
		service = new InventoryWatchService(configuredSettings(), mock(InventoryBatchLauncherService.class), queries,
				mock(OrganizationReconcileService.class), mock(OperationLockService.class), watchOnlyFactory(), recorder(),
				Clock.systemDefaultZone(), watchProps(false));

		Logger logger = (Logger) LoggerFactory.getLogger(InventoryWatchService.class);

		Level originalLevel = logger.getLevel();

		ListAppender<ILoggingEvent> appender = new ListAppender<>();

		appender.start();

		logger.setLevel(Level.DEBUG);
		logger.addAppender(appender);

		try {
			Thread.currentThread().interrupt();

			invokeNoArgs("pollSafely");
		} finally {
			Thread.interrupted();

			logger.detachAppender(appender);
			logger.setLevel(originalLevel);
		}

		assertThat(appender.list).noneMatch(event -> event.getLevel() == Level.ERROR)
				.anyMatch(event -> event.getLevel() == Level.DEBUG);
	}

	@Test
	void pollFailuresOutsideShutdownAreStillLoggedAsError() throws Exception {
		ExecutionQueryService queries = mock(ExecutionQueryService.class);

		when(queries.active()).thenThrow(new IllegalStateException("unexpected failure"));

		service = new InventoryWatchService(configuredSettings(), mock(InventoryBatchLauncherService.class), queries,
				mock(OrganizationReconcileService.class), mock(OperationLockService.class), watchOnlyFactory(), recorder(),
				Clock.systemDefaultZone(), watchProps(false));

		Logger logger = (Logger) LoggerFactory.getLogger(InventoryWatchService.class);

		ListAppender<ILoggingEvent> appender = new ListAppender<>();

		appender.start();

		logger.addAppender(appender);

		try {
			// A reused ForkJoinPool worker may carry a stray interrupt left by an earlier
			// parallel test; clear it so the "not interrupted" premise below is deterministic
			// regardless of test-scheduling order.
			Thread.interrupted();

			// Not shutting down and not interrupted: a genuine poll failure must surface as
			// ERROR, unchanged from the pre-shutdown-hardening behaviour.
			invokeNoArgs("pollSafely");
		} finally {
			logger.detachAppender(appender);
		}

		assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.ERROR);
	}

	private InventoryWatchProperties watchProps(boolean enabled) {
		InventoryWatchProperties properties = new InventoryWatchProperties();

		properties.setEnabled(enabled);

		return properties;
	}

	// A factory whose provider always declines, so it builds the portable
	// WatchService
	// source for the temp dir - the exact behaviour the watcher had before the USN
	// work
	// and what runs on the Linux CI (where the USN provider returns empty anyway).
	private FileChangeSourceFactory watchOnlyFactory() {
		return new FileChangeSourceFactory(_ -> Optional.empty());
	}

	private AppSettingService configuredSettings() {
		AppSettingService settings = mock(AppSettingService.class);

		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn(tempDir.toString());
		when(settings.booleanValue(SettingsConstants.WATCH_RECURSIVE, true)).thenReturn(true);
		when(settings.booleanValue(SettingsConstants.WATCH_INCLUDE_HIDDEN, false)).thenReturn(false);
		when(settings.booleanValue(SettingsConstants.WATCH_CALCULATE_HASHES, true)).thenReturn(true);
		when(settings.booleanValue(SettingsConstants.WATCH_FORCE_ANALYSIS, false)).thenReturn(false);

		return settings;
	}

	/**
	 * Builds the service and shuts down its internal poll executor so the
	 * background cycle can't race the reflection-driven private-method assertions
	 * below.
	 */
	private InventoryWatchService frozenService(AppSettingService settings, InventoryBatchLauncherService launcher,
			ExecutionQueryService queries, OrganizationReconcileService reconcileService) throws Exception {
		InventoryWatchService built = new InventoryWatchService(settings, launcher, queries, reconcileService,
				mock(OperationLockService.class), watchOnlyFactory(), recorder(), Clock.systemDefaultZone(),
				watchProps(true));

		Field executorField = InventoryWatchService.class.getDeclaredField("executor");

		executorField.setAccessible(true);

		((ExecutorService) executorField.get(built)).shutdownNow();

		return built;
	}

	private InventoryWatchService frozenServiceWith(ReconcileExecutionRecorder recorder,
			OrganizationReconcileService reconcileService) throws Exception {
		InventoryWatchService built = new InventoryWatchService(configuredSettings(),
				mock(InventoryBatchLauncherService.class), mock(ExecutionQueryService.class), reconcileService,
				mock(OperationLockService.class), watchOnlyFactory(), recorder, Clock.systemDefaultZone(),
				watchProps(true));

		Field executorField = InventoryWatchService.class.getDeclaredField("executor");

		executorField.setAccessible(true);

		((ExecutorService) executorField.get(built)).shutdownNow();

		return built;
	}

	private OrganizationReconcileResponse reconcile(long missingOnDisk) {
		return new OrganizationReconcileResponse(tempDir.toString(), true, false, 0, 0, missingOnDisk, 0, 0, List.of(),
				List.of(), List.of(), 0, 0, 0);
	}

	private OrganizationReconcileResponse repaired(long renamed, long repairedPaths, long markedMissing) {
		return new OrganizationReconcileResponse(tempDir.toString(), true, false, 0, 0, 0, 0, 0, List.of(), List.of(),
				List.of(), renamed, repairedPaths, markedMissing);
	}

	private ReconcileExecutionRecorder recorder() {
		return mock(ReconcileExecutionRecorder.class);
	}

	private ExecutionResponse activeExecution() {
		return new ExecutionResponse(1L, "INVENTORY", "PROCESSING_FILES", LocalDateTime.now(), null, tempDir.toString(),
				null, 0, 0, 0, 0, 0, 0, null, null, null, false);
	}

	private WatchEvent<?> overflowEvent() {
		return new WatchEvent<Object>() {

			@Override
			public Kind<Object> kind() {
				return StandardWatchEventKinds.OVERFLOW;
			}

			@Override
			public int count() {
				return 1;
			}

			@Override
			public Object context() {
				return null;
			}
		};
	}

	private PhysicalTreeWatcher watcherField() throws Exception {
		Field field = InventoryWatchService.class.getDeclaredField("watcher");

		field.setAccessible(true);

		return (PhysicalTreeWatcher) ((AtomicReference<?>) field.get(service)).get();
	}

	private void setField(String name, Object value) throws Exception {
		Field field = InventoryWatchService.class.getDeclaredField(name);

		field.setAccessible(true);
		field.set(service, value);
	}

	private boolean booleanField(String name) throws Exception {
		Field field = InventoryWatchService.class.getDeclaredField(name);

		field.setAccessible(true);

		return field.getBoolean(service);
	}

	private long longField(String name) throws Exception {
		Field field = InventoryWatchService.class.getDeclaredField(name);

		field.setAccessible(true);

		return field.getLong(service);
	}

	private Object objectField(String name) throws Exception {
		Field field = InventoryWatchService.class.getDeclaredField(name);

		field.setAccessible(true);

		return field.get(service);
	}

	private void invokeBoolean(String name, boolean argument) throws Exception {
		Method method = InventoryWatchService.class.getDeclaredMethod(name, boolean.class);

		method.setAccessible(true);
		method.invoke(service, argument);
	}

	private void invokeNoArgs(String name) throws Exception {
		Method method = InventoryWatchService.class.getDeclaredMethod(name);

		method.setAccessible(true);
		method.invoke(service);
	}
}