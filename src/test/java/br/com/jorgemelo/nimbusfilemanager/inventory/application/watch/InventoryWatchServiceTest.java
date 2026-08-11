package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationPathKey;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.InventoryLauncherService;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryWatchStatus;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.FileChangeSourceFactory;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.application.BackgroundWorkGate;
import br.com.jorgemelo.nimbusfilemanager.shared.application.InMemorySelfWrittenPaths;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.InventoryWatchProperties;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

// Manipulates the process-global logback logger of InventoryWatchService and
// watches a real poll thread against wall-clock windows; runs alone so it is
// immune to interference from concurrently-scheduled test classes.
@Isolated
class InventoryWatchServiceTest {

	private final SelfWrittenPathRegistry pathRegistry = new SelfWrittenPathRegistry(new InMemorySelfWrittenPaths(),
			Clock.systemDefaultZone());
	private final ScanExclusionService exclusions = mock(ScanExclusionService.class);
	private final ExecutionEnqueueService enqueueService = mock(ExecutionEnqueueService.class);
	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);

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

		InventoryLauncherService launcher = mock(InventoryLauncherService.class);

		ExecutionQueryService queries = mock(ExecutionQueryService.class);

		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn(tempDir.toString());
		when(settings.booleanValue(SettingsConstants.WATCH_RECURSIVE, true)).thenReturn(true);
		when(settings.booleanValue(SettingsConstants.WATCH_INCLUDE_HIDDEN, false)).thenReturn(false);
		when(settings.booleanValue(SettingsConstants.WATCH_CALCULATE_HASHES, true)).thenReturn(true);
		when(settings.booleanValue(SettingsConstants.WATCH_FORCE_ANALYSIS, false)).thenReturn(false);
		when(queries.active()).thenReturn(Optional.empty());

		service = new InventoryWatchService(settings, launcher, queries, enqueue(), executions(),
				mock(OperationLockService.class), watchOnlyFactory(), Clock.systemDefaultZone(), watchProps(true),
				new BackgroundWorkGate());
		service.reconfigure();

		Files.writeString(tempDir.resolve("new-photo.jpg"), "test");

		verify(launcher, timeout(6_000)).launch(any(), any());
	}

	@Test
	void applicationStartupShouldInventoryFilesAlreadyInConfiguredFolder() throws Exception {
		Files.writeString(tempDir.resolve("already-there.txt"), "");

		AppSettingService settings = mock(AppSettingService.class);

		InventoryLauncherService launcher = mock(InventoryLauncherService.class);

		ExecutionQueryService queries = mock(ExecutionQueryService.class);

		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn(tempDir.toString());
		when(settings.booleanValue(SettingsConstants.WATCH_RECURSIVE, true)).thenReturn(true);
		when(settings.booleanValue(SettingsConstants.WATCH_INCLUDE_HIDDEN, false)).thenReturn(false);
		when(settings.booleanValue(SettingsConstants.WATCH_CALCULATE_HASHES, true)).thenReturn(true);
		when(settings.booleanValue(SettingsConstants.WATCH_FORCE_ANALYSIS, false)).thenReturn(false);

		service = new InventoryWatchService(settings, launcher, queries, enqueue(), executions(),
				mock(OperationLockService.class), watchOnlyFactory(), Clock.systemDefaultZone(), watchProps(true),
				new BackgroundWorkGate());

		service.startConfiguredMonitor();

		verify(launcher).launch(any(), any());
	}

	@Test
	void monitorReconfigurationShouldImmediatelyInventoryTheNewFolder() {
		AppSettingService settings = mock(AppSettingService.class);

		InventoryLauncherService launcher = mock(InventoryLauncherService.class);

		ExecutionQueryService queries = mock(ExecutionQueryService.class);

		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn(tempDir.toString());
		when(queries.active()).thenReturn(Optional.empty());

		service = new InventoryWatchService(settings, launcher, queries, enqueue(), executions(),
				mock(OperationLockService.class), watchOnlyFactory(), Clock.systemDefaultZone(), watchProps(true),
				new BackgroundWorkGate());
		service.reconfigureAndInventory();

		verify(launcher).launch(any(), any());

		assertThat(service.status().running()).isTrue();
	}

	@Test
	void applicationStartupShouldNotLaunchInventoryWithoutConfiguredFolder() {
		AppSettingService settings = mock(AppSettingService.class);

		InventoryLauncherService launcher = mock(InventoryLauncherService.class);

		ExecutionQueryService queries = mock(ExecutionQueryService.class);

		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn("");

		service = new InventoryWatchService(settings, launcher, queries, enqueue(), executions(),
				mock(OperationLockService.class), watchOnlyFactory(), Clock.systemDefaultZone(), watchProps(true),
				new BackgroundWorkGate());

		service.startConfiguredMonitor();

		verify(launcher, never()).launch(any(), any());
	}

	@Test
	void deletedFileShouldTriggerAutomaticReconciliationAndBatch() throws Exception {
		Path existing = Files.writeString(tempDir.resolve("existing.jpg"), "test");

		AppSettingService settings = mock(AppSettingService.class);

		InventoryLauncherService launcher = mock(InventoryLauncherService.class);

		ExecutionQueryService queries = mock(ExecutionQueryService.class);

		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn(tempDir.toString());
		when(settings.booleanValue(SettingsConstants.WATCH_RECURSIVE, true)).thenReturn(true);
		when(settings.booleanValue(SettingsConstants.WATCH_INCLUDE_HIDDEN, false)).thenReturn(false);
		when(settings.booleanValue(SettingsConstants.WATCH_CALCULATE_HASHES, true)).thenReturn(true);
		when(settings.booleanValue(SettingsConstants.WATCH_FORCE_ANALYSIS, false)).thenReturn(false);
		when(queries.active()).thenReturn(Optional.empty());

		service = new InventoryWatchService(settings, launcher, queries, enqueue(), executions(),
				mock(OperationLockService.class), watchOnlyFactory(), Clock.systemDefaultZone(), watchProps(true),
				new BackgroundWorkGate());

		service.reconfigure();

		Files.delete(existing);

		verify(enqueueService, timeout(6_000)).enqueue(any());
		verify(launcher, timeout(6_000)).launch(any(), any());
	}

	/**
	 * Regression test for the race condition where {@code reconfigure()} - called
	 * from the web request thread when an admin changes the monitored folder -
	 * could swap/close the {@code PhysicalTreeWatcher} while the scheduled poll
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

		InventoryLauncherService launcher = mock(InventoryLauncherService.class);

		ExecutionQueryService queries = mock(ExecutionQueryService.class);

		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn("");

		service = new InventoryWatchService(settings, launcher, queries, enqueue(), executions(),
				mock(OperationLockService.class), watchOnlyFactory(), Clock.systemDefaultZone(), watchProps(true),
				new BackgroundWorkGate());

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
		// acquire it - proving it shares the lock with reconfigure()/stopSource()
		// instead of running free. Waited for rather than sampled after a fixed pause:
		// the state is what is being asserted, so the test should watch for it.
		await().atMost(Duration.ofSeconds(5))
				.untilAsserted(() -> assertThat(pollThread.getState()).isEqualTo(Thread.State.BLOCKED));

		releaseMonitor.countDown();

		pollThread.join(5_000);

		holder.join(5_000);

		assertThat(pollThread.isAlive()).isFalse();
	}

	@Test
	void reconfigureWithBlankFolderLeavesMonitorUnconfigured() throws Exception {
		AppSettingService settings = mock(AppSettingService.class);

		InventoryLauncherService launcher = mock(InventoryLauncherService.class);

		ExecutionQueryService queries = mock(ExecutionQueryService.class);

		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn("");

		service = frozenService(settings, launcher, queries);
		service.reconfigure();

		assertThat(service.status().running()).isFalse();
		assertThat(service.status().configured()).isFalse();
	}

	@Test
	void reconfigureWithMissingFolderReportsError() throws Exception {
		AppSettingService settings = mock(AppSettingService.class);

		InventoryLauncherService launcher = mock(InventoryLauncherService.class);

		ExecutionQueryService queries = mock(ExecutionQueryService.class);

		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, ""))
				.thenReturn(tempDir.resolve("does-not-exist").toString());

		service = frozenService(settings, launcher, queries);

		service.reconfigure();

		assertThat(service.status().running()).isFalse();
		assertThat(service.status().configured()).isTrue();
		assertThat(service.status().error()).isNotBlank();
	}

	@Test
	void reconfigureAndInventoryQueuesInventoryWhenExecutionActive() throws Exception {
		AppSettingService settings = mock(AppSettingService.class);

		InventoryLauncherService launcher = mock(InventoryLauncherService.class);

		ExecutionQueryService queries = mock(ExecutionQueryService.class);

		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn(tempDir.toString());
		when(queries.active()).thenReturn(Optional.of(activeExecution()));

		service = frozenService(settings, launcher, queries);
		service.reconfigureAndInventory();

		// An inventory is already running, so the reconfiguration must queue the work
		// (inventoryPending) instead of launching a second batch immediately.
		verify(launcher, never()).launch(any(), any());

		assertThat(service.status().running()).isTrue();
		assertThat(booleanField("inventoryPending")).isTrue();
	}

	@Test
	void pauseStopsMonitoringAndClearsPending() throws Exception {
		service = frozenService(configuredSettings(), mock(InventoryLauncherService.class),
				mock(ExecutionQueryService.class));

		service.reconfigure();

		assertThat(service.status().running()).isTrue();

		service.pause();

		assertThat(service.status().running()).isFalse();
		assertThat(service.status().error()).contains("Trocando");
		assertThat(booleanField("inventoryPending")).isFalse();
	}

	@Test
	void launchPendingInventorySkipsWhileWithinDebounceWindow() throws Exception {
		InventoryLauncherService launcher = mock(InventoryLauncherService.class);

		service = frozenService(configuredSettings(), launcher, mock(ExecutionQueryService.class));

		service.reconfigure();

		setField("inventoryPending", true);
		setField("lastEventMillis", System.currentTimeMillis());

		invokeBoolean("launchPendingInventory", false);

		verify(launcher, never()).launch(any(), any());
	}

	@Test
	void launchPendingInventoryLaunchesAfterDebounceElapses() throws Exception {
		InventoryLauncherService launcher = mock(InventoryLauncherService.class);

		service = frozenService(configuredSettings(), launcher, mock(ExecutionQueryService.class));
		service.reconfigure();

		setField("inventoryPending", true);
		setField("lastEventMillis", System.currentTimeMillis() - 5_000L);

		invokeBoolean("launchPendingInventory", false);

		verify(enqueueService).enqueue(any());
		verify(launcher).launch(any(), any());

		assertThat(booleanField("inventoryPending")).isFalse();
	}

	@Test
	void launchPendingInventorySkipsWhenExecutionActive() throws Exception {
		InventoryLauncherService launcher = mock(InventoryLauncherService.class);

		service = frozenService(configuredSettings(), launcher, mock(ExecutionQueryService.class));

		service.reconfigure();

		setField("inventoryPending", true);
		setField("lastEventMillis", System.currentTimeMillis() - 5_000L);

		invokeBoolean("launchPendingInventory", true);

		verify(launcher, never()).launch(any(), any());
	}

	/**
	 * The reconcile a file event asks for is queued like any other execution, and
	 * carries what the worker needs to run it: the watched folder, the file-event
	 * trigger, and the folder as dedup key - which is what keeps a burst of
	 * changes from queueing a pass per change.
	 */
	@Test
	void debouncedFileChangeQueuesAReconcileForTheWatchedFolder() throws Exception {
		service = frozenService(configuredSettings(), mock(InventoryLauncherService.class),
				mock(ExecutionQueryService.class));
		service.reconfigure();

		setField("inventoryPending", true);
		setField("lastEventMillis", System.currentTimeMillis() - 5_000L);

		invokeBoolean("launchPendingInventory", false);

		ArgumentCaptor<Execution> queued = ArgumentCaptor.forClass(Execution.class);

		verify(enqueueService).enqueue(queued.capture());

		assertThat(queued.getValue().getExecutionType()).isEqualTo(ExecutionType.RECONCILE);
		assertThat(queued.getValue().getTriggerEvent()).isEqualTo(ExecutionTrigger.FILE_EVENT);
		assertThat(queued.getValue().getSourcePath()).isEqualTo(tempDir.toAbsolutePath().normalize().toString());
		assertThat(queued.getValue().getDedupKey())
				.isEqualTo(OperationPathKey.canonical(tempDir.toAbsolutePath().normalize()));
	}

	/**
	 * The footer's "last reconciliation" comes from the catalog, so a pass the
	 * worker ran - in another process, after this one started - is what the screen
	 * shows. Everything else about the watcher's state is still the watcher's own.
	 */
	@Test
	void statusReportsTheLastFinishedReconcile() throws Exception {
		LocalDateTime finishedAt = LocalDateTime.of(2026, Month.AUGUST, 5, 14, 30);

		Execution reconcile = Execution.builder().executionType(ExecutionType.RECONCILE)
				.status(ExecutionStatus.FINISHED).sourcePath(tempDir.toString()).finishedAt(finishedAt)
				.repairedItems(7).build();

		when(executionRepository.findFirstByExecutionTypeAndStatusOrderByFinishedAtDesc(ExecutionType.RECONCILE,
				ExecutionStatus.FINISHED)).thenReturn(Optional.of(reconcile));

		service = frozenService(configuredSettings(), mock(InventoryLauncherService.class),
				mock(ExecutionQueryService.class));
		service.reconfigure();

		assertThat(service.status().lastReconciliation()).isEqualTo(finishedAt);
		assertThat(service.status().lastReconciliationRepaired()).isEqualTo(7);
		assertThat(service.status().running()).isTrue();
		assertThat(service.status().folder()).isEqualTo(tempDir.toString());
	}

	/** Before the first pass finishes there is nothing to date. */
	@Test
	void statusHasNoReconciliationUntilOneFinishes() throws Exception {
		service = frozenService(configuredSettings(), mock(InventoryLauncherService.class),
				mock(ExecutionQueryService.class));
		service.reconfigure();

		assertThat(service.status().lastReconciliation()).isNull();
		assertThat(service.status().lastReconciliationRepaired()).isZero();
	}

	/**
	 * Every burst asks, and the queue is what refuses the repeat - it rejects a
	 * second RECONCILE for the same dedup key, which
	 * {@code ExecutionQueueIntegrationTest} asserts against a real database. The
	 * watcher deliberately does not try to remember what it queued: that answer
	 * lives where a restart cannot lose it, and a second process asking for the
	 * same folder has to be refused too.
	 */
	@Test
	void keepsAskingAndLeavesTheDuplicateForTheQueueToRefuse() throws Exception {
		when(enqueueService.enqueue(any())).thenReturn(Optional.empty());

		service = frozenService(configuredSettings(), mock(InventoryLauncherService.class),
				mock(ExecutionQueryService.class));
		service.reconfigure();

		for (int burst = 0; burst < 3; burst++) {
			setField("inventoryPending", true);
			setField("lastEventMillis", System.currentTimeMillis() - 5_000L);

			invokeBoolean("launchPendingInventory", false);
		}

		verify(enqueueService, times(3)).enqueue(any());
	}

	@Test
	void overflowEventForcesAnEarlyReInventory() throws Exception {
		service = frozenService(configuredSettings(), mock(InventoryLauncherService.class),
				mock(ExecutionQueryService.class));

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

		InventoryLauncherService launcher = mock(InventoryLauncherService.class);

		ExecutionQueryService queries = mock(ExecutionQueryService.class);

		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn(tempDir.toString());

		service = frozenService(settings, launcher, queries);

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
		service = new InventoryWatchService(configuredSettings(), mock(InventoryLauncherService.class),
				mock(ExecutionQueryService.class), enqueue(), executions(), mock(OperationLockService.class),
				watchOnlyFactory(), Clock.systemDefaultZone(), watchProps(true), new BackgroundWorkGate());

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

		service = new InventoryWatchService(configuredSettings(), mock(InventoryLauncherService.class), queries,
				enqueue(), executions(), mock(OperationLockService.class), watchOnlyFactory(),
				Clock.systemDefaultZone(), watchProps(true), new BackgroundWorkGate());

		service.stop();

		Field pollTaskField = InventoryWatchService.class.getDeclaredField("pollTask");

		pollTaskField.setAccessible(true);

		ScheduledFuture<?> pollTask = (ScheduledFuture<?>) ((AtomicReference<?>) pollTaskField.get(service)).get();

		assertThat((Object) pollTask).isNotNull();
		assertThat(pollTask.isCancelled()).isTrue();
	}

	@Test
	void stopIsIdempotentAndSafeToCallTwice() throws Exception {
		service = new InventoryWatchService(configuredSettings(), mock(InventoryLauncherService.class),
				mock(ExecutionQueryService.class), enqueue(), executions(), mock(OperationLockService.class),
				watchOnlyFactory(), Clock.systemDefaultZone(), watchProps(false), new BackgroundWorkGate());

		service.stop();
		service.stop();

		assertThat(booleanField("shuttingDown")).isTrue();
	}

	@Test
	void pollSafelyDoesNothingAfterStop() throws Exception {
		ExecutionQueryService queries = mock(ExecutionQueryService.class);

		InventoryLauncherService launcher = mock(InventoryLauncherService.class);

		service = new InventoryWatchService(configuredSettings(), launcher, queries, enqueue(), executions(),
				mock(OperationLockService.class), watchOnlyFactory(), Clock.systemDefaultZone(), watchProps(false),
				new BackgroundWorkGate());

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
		service = new InventoryWatchService(configuredSettings(), mock(InventoryLauncherService.class), queries,
				enqueue(), executions(), mock(OperationLockService.class), watchOnlyFactory(),
				Clock.systemDefaultZone(), watchProps(false), new BackgroundWorkGate());

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

		service = new InventoryWatchService(configuredSettings(), mock(InventoryLauncherService.class), queries,
				enqueue(), executions(), mock(OperationLockService.class), watchOnlyFactory(),
				Clock.systemDefaultZone(), watchProps(false), new BackgroundWorkGate());

		Logger logger = (Logger) LoggerFactory.getLogger(InventoryWatchService.class);

		ListAppender<ILoggingEvent> appender = new ListAppender<>();

		appender.start();

		logger.addAppender(appender);

		try {
			// A reused ForkJoinPool worker may carry a stray interrupt left by an earlier
			// parallel test; clear it so the "not interrupted" premise below is
			// deterministic regardless of test-scheduling order.
			Thread.interrupted();

			// Not shutting down and not interrupted: a genuine poll failure must surface as
			// ERROR, unchanged from the pre-shutdown-hardening behaviour.
			invokeNoArgs("pollSafely");
		} finally {
			logger.detachAppender(appender);
		}

		assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.ERROR);
	}

	/**
	 * <b>The startup race, in the order production actually runs it.</b>
	 *
	 * <p>
	 * The poll thread starts half a second after the bean is built and the context
	 * takes tens of seconds more to be ready, so the poll is what adopts the
	 * configured folder; {@code ApplicationReadyEvent} then arrives and used to
	 * adopt it a second time - closing a working handle, reopening it, and running
	 * a second USN catch-up. One folder, one source.
	 */
	@Test
	void thePollAdoptingFirstLeavesNothingForApplicationReadyToAdopt() {
		AppSettingService settings = configuredSettings();

		InventoryLauncherService launcher = mock(InventoryLauncherService.class);

		when(launcher.launch(any(), any())).thenReturn(mock(ExecutionResponse.class));

		AtomicInteger created = new AtomicInteger();

		service = new InventoryWatchService(settings, launcher, idleQueries(), enqueue(), executions(),
				mock(OperationLockService.class), countingFactory(created), Clock.systemDefaultZone(),
				watchProps(true), new BackgroundWorkGate());

		await().atMost(Duration.ofSeconds(6)).until(() -> created.get() == 1);

		service.startConfiguredMonitor();

		// Held rather than sampled: the second adoption would show up as a second
		// source at some point in the next poll cycles, so the assertion has to be
		// that it never does.
		await().during(Duration.ofMillis(1_200)).atMost(Duration.ofSeconds(4))
				.untilAsserted(() -> assertThat(created)
						.as("a second adoption would open a second handle and run a second catch-up").hasValue(1));

		verify(launcher, times(1)).launch(any(), any());
	}

	/**
	 * And the other way round, which is what a slow first poll or a fast context
	 * produces: the event adopts, and every poll after it has to find the work
	 * already done.
	 */
	@Test
	void applicationReadyAdoptingFirstLeavesNothingForThePollToAdopt() {
		AppSettingService settings = configuredSettings();

		InventoryLauncherService launcher = mock(InventoryLauncherService.class);

		when(launcher.launch(any(), any())).thenReturn(mock(ExecutionResponse.class));

		AtomicInteger created = new AtomicInteger();

		service = new InventoryWatchService(settings, launcher, idleQueries(), enqueue(), executions(),
				mock(OperationLockService.class), countingFactory(created), Clock.systemDefaultZone(),
				watchProps(true), new BackgroundWorkGate());

		service.startConfiguredMonitor();

		assertThat(created).hasValue(1);

		await().during(Duration.ofMillis(1_500)).atMost(Duration.ofSeconds(5))
				.untilAsserted(() -> assertThat(created)
						.as("several poll cycles have run by now and none of them re-adopted").hasValue(1));

		verify(launcher, times(1)).launch(any(), any());
	}

	/**
	 * Idempotence is about the folder being the same, not about never adopting
	 * again: pointing the library somewhere else still tears the old watch down
	 * and builds a new one.
	 */
	@Test
	void aRealFolderChangeStillReplacesTheSource(@TempDir Path other) {
		AppSettingService settings = configuredSettings();

		InventoryLauncherService launcher = mock(InventoryLauncherService.class);

		when(launcher.launch(any(), any())).thenReturn(mock(ExecutionResponse.class));

		AtomicInteger created = new AtomicInteger();

		service = new InventoryWatchService(settings, launcher, idleQueries(), enqueue(), executions(),
				mock(OperationLockService.class), countingFactory(created), Clock.systemDefaultZone(),
				watchProps(false), new BackgroundWorkGate());

		assertThat(service.reconfigure()).isTrue();
		assertThat(service.reconfigure()).as("the same folder is already being watched").isFalse();

		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenReturn(other.toString());

		assertThat(service.reconfigure()).as("a different folder is a different watch").isTrue();
		assertThat(created).hasValue(2);
		assertThat(service.status().folder()).isEqualTo(other.toAbsolutePath().normalize().toString());
	}

	/** The recursion flag is part of what the source was built with. */
	@Test
	void changingOnlyTheRecursionSettingRebuildsTheSource() {
		AppSettingService settings = configuredSettings();

		AtomicInteger created = new AtomicInteger();

		service = new InventoryWatchService(settings, mock(InventoryLauncherService.class), idleQueries(), enqueue(),
				executions(), mock(OperationLockService.class), countingFactory(created), Clock.systemDefaultZone(),
				watchProps(false), new BackgroundWorkGate());

		assertThat(service.reconfigure()).isTrue();

		when(settings.booleanValue(SettingsConstants.WATCH_RECURSIVE, true)).thenReturn(false);

		assertThat(service.reconfigure()).isTrue();
		assertThat(created).hasValue(2);
	}

	/**
	 * Counts how many sources were really built. The provider declines, as in the
	 * portable case, so what is counted is one {@code PhysicalTreeWatcher} per
	 * adoption - which is exactly the handle the duplicated startup was opening
	 * twice.
	 */
	private FileChangeSourceFactory countingFactory(AtomicInteger created) {
		return new FileChangeSourceFactory(_ -> {
			created.incrementAndGet();

			return Optional.empty();
		}, pathRegistry, exclusions);
	}

	/** Nothing running and nothing locked, so the poll reaches its decisions. */
	private ExecutionQueryService idleQueries() {
		ExecutionQueryService queries = mock(ExecutionQueryService.class);

		when(queries.active()).thenReturn(Optional.empty());

		return queries;
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
	/**
	 * When the platform refuses to open a watcher the service must stay up and
	 * report why on the status screen, instead of leaving the app half-started with
	 * no explanation.
	 */
	@Test
	void aFailureToOpenTheWatcherShouldSurfaceOnTheStatusInsteadOfPropagating() {
		FileChangeSourceFactory failing = new FileChangeSourceFactory(_ -> {
			throw new IllegalStateException("no watcher available on this volume");
		}, pathRegistry, exclusions);

		service = new InventoryWatchService(configuredSettings(), mock(InventoryLauncherService.class),
				mock(ExecutionQueryService.class), enqueue(), executions(), mock(OperationLockService.class), failing,
				Clock.systemDefaultZone(), watchProps(true), new BackgroundWorkGate());

		service.reconfigure();

		InventoryWatchStatus status = service.status();

		Assertions.assertThat(status.running()).isFalse();
		Assertions.assertThat(status.configured()).isTrue();
		Assertions.assertThat(status.error()).contains("no watcher available on this volume");
	}

	/**
	 * The identity the poll follows is folder <em>and</em> depth, not the folder
	 * alone. Nothing in the product reaches this today - every writer of the depth
	 * setting calls a reconfigure of its own - so what this holds is an invariant
	 * rather than a path that was broken. It is held because the folder already
	 * changes from another process, the library switch being a worker's job, and
	 * the day the depth follows it the poll must notice.
	 */
	@Test
	void thePollFollowsTheDepthTheSettingAsksForAndNotOnlyTheFolder() throws Exception {
		AppSettingService settings = configuredSettings();

		AtomicInteger created = new AtomicInteger();

		service = new InventoryWatchService(settings, mock(InventoryLauncherService.class), idleQueries(), enqueue(),
				executions(), mock(OperationLockService.class), countingFactory(created), Clock.systemDefaultZone(),
				watchProps(false), new BackgroundWorkGate());

		invokeNoArgs("pollSafely");

		assertThat(created).as("the poll adopts the configured library").hasValue(1);

		invokeNoArgs("pollSafely");

		assertThat(created).as("nothing changed, so nothing is torn down").hasValue(1);

		when(settings.booleanValue(SettingsConstants.WATCH_RECURSIVE, true)).thenReturn(false);

		invokeNoArgs("pollSafely");

		assertThat(created).as("a different depth is a different watch").hasValue(2);

		invokeNoArgs("pollSafely");

		assertThat(created).as("and the new depth is what is being watched now").hasValue(2);
	}

	/**
	 * A change asked for the pass and the queue refused it, so that change is
	 * still uncatalogued - and nothing else goes looking for it, because the
	 * reconcile retires what left rather than cataloguing what arrived. The
	 * pending therefore has to outlive the failure and be asked for again,
	 * instead of being cleared on the way into a call that never happened.
	 */
	@Test
	void aPendingOutlivesAQueueThatRefusedIt() throws Exception {
		InventoryLauncherService launcher = mock(InventoryLauncherService.class);

		when(enqueueService.enqueue(any())).thenThrow(new IllegalStateException("This connection has been closed."));

		service = new InventoryWatchService(configuredSettings(), launcher, idleQueries(), enqueue(), executions(),
				mock(OperationLockService.class), watchOnlyFactory(), Clock.systemDefaultZone(), watchProps(false),
				new BackgroundWorkGate());

		service.reconfigure();

		setField("inventoryPending", true);
		setField("lastEventMillis", 0L);

		invokeNoArgs("pollSafely");

		assertThat(booleanField("inventoryPending")).as("the change on disk is still uncatalogued").isTrue();

		verify(launcher, never()).launch(any(), any());
	}

	/** And it is cleared once the work really is on the queue. */
	@Test
	void aPendingIsClearedOnceTheQueueTookTheWork() throws Exception {
		InventoryLauncherService launcher = mock(InventoryLauncherService.class);

		service = new InventoryWatchService(configuredSettings(), launcher, idleQueries(), enqueue(), executions(),
				mock(OperationLockService.class), watchOnlyFactory(), Clock.systemDefaultZone(), watchProps(false),
				new BackgroundWorkGate());

		service.reconfigure();

		setField("inventoryPending", true);
		setField("lastEventMillis", 0L);

		invokeNoArgs("pollSafely");

		assertThat(booleanField("inventoryPending")).isFalse();

		verify(enqueueService).enqueue(any());
		verify(launcher).launch(any(), any());
	}

	/**
	 * A folder is a change like any other here, and the watcher must not screen
	 * it out. The Windows sources report directories deliberately: a folder moved
	 * into the library arrives already full, its files were never created under
	 * the watched tree and raise no notification of their own, and the reconcile
	 * that follows retires what left rather than cataloguing what arrived - so
	 * the folder's own path is the only notice they ever produce.
	 */
	@Test
	void aFolderThatArrivesWakesTheWatcherLikeAnyOtherChange() throws Exception {
		Path album = Files.createDirectory(tempDir.resolve("album-moved-in"));

		Files.writeString(album.resolve("holiday.jpg"), "jpg");

		InventoryLauncherService launcher = mock(InventoryLauncherService.class);

		AtomicReference<RecordingFileChangeSource> built = new AtomicReference<>();

		service = new InventoryWatchService(configuredSettings(), launcher, idleQueries(), enqueue(), executions(),
				mock(OperationLockService.class), recordingFactory(built), Clock.systemDefaultZone(),
				watchProps(false), new BackgroundWorkGate());

		service.reconfigure();

		built.get().reportLive(album);

		// The first cycle takes the change in and stamps the debounce clock; the
		// second finds the quiet the debounce is waiting for.
		pollCycle();
		pollCycle();

		verify(launcher).launch(any(), any());
	}

	/** One cycle of the loop the scheduler runs, with the debounce satisfied. */
	private void pollCycle() throws Exception {
		setField("lastEventMillis", 0L);

		invokeNoArgs("pollSafely");
	}

	/**
	 * Hands the service a source the test fills by hand, which is what stands in
	 * for the Windows ones: the portable {@code WatchService} reports only what it
	 * really sees, so it cannot be asked for a particular kind of change.
	 */
	private FileChangeSourceFactory recordingFactory(AtomicReference<RecordingFileChangeSource> built) {
		return new FileChangeSourceFactory(root -> {
			RecordingFileChangeSource created = new RecordingFileChangeSource(root, List.of(), null);

			built.set(created);

			return Optional.of(created);
		}, pathRegistry, exclusions);
	}

	private FileChangeSourceFactory watchOnlyFactory() {
		return new FileChangeSourceFactory(_ -> Optional.empty(), pathRegistry, exclusions);
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
	private InventoryWatchService frozenService(AppSettingService settings, InventoryLauncherService launcher,
			ExecutionQueryService queries) throws Exception {
		InventoryWatchService built = new InventoryWatchService(settings, launcher, queries, enqueue(), executions(),
				mock(OperationLockService.class), watchOnlyFactory(), Clock.systemDefaultZone(), watchProps(true),
				new BackgroundWorkGate());

		Field executorField = InventoryWatchService.class.getDeclaredField("executor");

		executorField.setAccessible(true);

		((ExecutorService) executorField.get(built)).shutdownNow();

		return built;
	}

	private ExecutionResponse activeExecution() {
		return new ExecutionResponse(1L, "INVENTORY", "RUNNING", LocalDateTime.now(), null, tempDir.toString(),
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

		Object source = ((AtomicReference<?>) field.get(service)).get();

		// The factory hands the service a wrapper that filters out the changes the
		// application wrote itself; the events these tests inject belong to the real
		// watcher underneath it.
		Field delegate = source.getClass().getDeclaredField("delegate");

		delegate.setAccessible(true);

		return (PhysicalTreeWatcher) delegate.get(source);
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

	/**
	 * The same mock every time, so a test can verify what was queued without
	 * having to thread it through the constructor call it does not care about.
	 */
	private ExecutionEnqueueService enqueue() {
		return enqueueService;
	}

	private ExecutionRepository executions() {
		return executionRepository;
	}
}