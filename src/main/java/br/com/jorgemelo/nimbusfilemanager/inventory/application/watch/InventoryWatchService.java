package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ReconcileExecutionRecorder;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.batch.InventoryBatchLauncherService;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryRequest;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryWatchStatus;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.FileChangeSource;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.FileChangeSourceFactory;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationReconcileService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationReconcileResponse;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.InventoryWatchProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class InventoryWatchService extends LocalizedComponent {

	private static final long POLL_MILLIS = 500;
	private static final long DEBOUNCE_MILLIS = 2_000;
	private static final long SHUTDOWN_DRAIN_MILLIS = 2_000;

	private final AppSettingService appSettingService;
	private final InventoryBatchLauncherService inventoryBatchLauncherService;
	private final ExecutionQueryService executionQueryService;
	private final OrganizationReconcileService organizationReconcileService;
	private final OperationLockService operationLockService;
	private final FileChangeSourceFactory fileChangeSourceFactory;
	private final ReconcileExecutionRecorder reconcileExecutionRecorder;
	private final Clock clock;
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "nimbus-file-manager-folder-watch");

		thread.setDaemon(true);

		return thread;
	});

	private final AtomicReference<FileChangeSource> watcher = new AtomicReference<>();
	private final AtomicReference<InventoryWatchStatus> status = new AtomicReference<>(
			InventoryWatchStatus.unconfigured());
	private volatile long lastEventMillis;
	private volatile boolean inventoryPending;
	private volatile boolean shuttingDown;
	private volatile LocalDateTime lastReconciliation;
	private volatile long lastReconciliationRepaired;
	private final AtomicReference<ScheduledFuture<?>> pollTask = new AtomicReference<>();
	private final AtomicBoolean stopped = new AtomicBoolean();

	@Autowired
	public InventoryWatchService(AppSettingService appSettingService,
			InventoryBatchLauncherService inventoryBatchLauncherService, ExecutionQueryService executionQueryService,
			OrganizationReconcileService organizationReconcileService, OperationLockService operationLockService,
			FileChangeSourceFactory fileChangeSourceFactory, ReconcileExecutionRecorder reconcileExecutionRecorder,
			Clock clock, InventoryWatchProperties watchProperties) {
		this.appSettingService = appSettingService;
		this.inventoryBatchLauncherService = inventoryBatchLauncherService;
		this.executionQueryService = executionQueryService;
		this.organizationReconcileService = organizationReconcileService;
		this.operationLockService = operationLockService;
		this.fileChangeSourceFactory = fileChangeSourceFactory;
		this.reconcileExecutionRecorder = reconcileExecutionRecorder;
		this.clock = clock;

		// Disabled in the test JVM (InventoryWatchProperties.enabled=false via
		// surefire):
		// the @SpringBootTest contexts don't exercise the watcher, so a 500ms poll
		// hitting
		// the real DB per context only wastes cycles and races the
		// Testcontainers/Hikari
		// teardown at JVM exit. Production keeps the default (true).
		if (watchProperties.isEnabled()) {
			pollTask.set(
					executor.scheduleWithFixedDelay(this::pollSafely, POLL_MILLIS, POLL_MILLIS, TimeUnit.MILLISECONDS));
		}
	}

	@EventListener(ApplicationReadyEvent.class)
	public void startConfiguredMonitor() {
		reconfigure();

		if (status.get().running()) {
			log.info("Launching startup inventory for configured folder {}", status.get().folder());

			inventoryBatchLauncherService.launch(watchRequest(), ExecutionTrigger.MANUAL);
		}
	}

	public synchronized void reconfigure() {
		stopSource();

		String configuredFolder = appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "");

		if (configuredFolder.isBlank()) {
			status.set(InventoryWatchStatus.unconfigured());

			return;
		}

		Path folder = Path.of(configuredFolder).toAbsolutePath().normalize();

		if (!Files.isDirectory(folder)) {
			status.set(new InventoryWatchStatus(folder.toString(), true, false, null,
					message("backend.watch.folderMissing"), lastReconciliation, lastReconciliationRepaired));

			return;
		}

		try {
			watcher.set(fileChangeSourceFactory.create(folder,
					appSettingService.booleanValue(SettingsConstants.WATCH_RECURSIVE, true)));

			status.set(new InventoryWatchStatus(folder.toString(), true, true, null, null, lastReconciliation,
					lastReconciliationRepaired));

			log.info("Inventory watcher is monitoring {}", folder);
		} catch (Exception exception) {
			status.set(new InventoryWatchStatus(folder.toString(), true, false, null, exception.getMessage(),
					lastReconciliation, lastReconciliationRepaired));

			log.error("Could not start inventory watcher for {}", folder, exception);
		}
	}

	public void reconfigureAndInventory() {
		reconfigure();

		if (status.get().running()) {
			if (executionQueryService.active().isPresent()) {
				inventoryPending = true;

				lastEventMillis = 0;

				log.info("Inventory queued after monitor reconfiguration for folder {}", status.get().folder());
			} else {
				log.info("Launching inventory after monitor reconfiguration for folder {}", status.get().folder());

				inventoryBatchLauncherService.launch(watchRequest(), ExecutionTrigger.MANUAL);
			}
		}
	}

	public InventoryWatchStatus status() {
		return status.get();
	}

	public synchronized void pause() {
		stopSource();

		inventoryPending = false;

		InventoryWatchStatus current = status.get();

		status.set(new InventoryWatchStatus(current.folder(), current.configured(), false, current.lastEvent(),
				message("backend.watch.switchingLibrary"), lastReconciliation, lastReconciliationRepaired));
	}

	private void pollSafely() {
		try {
			pollEvents();

			// Bail before any DB access once shutdown has begun: stop() flips this flag
			// (and
			// interrupts this thread) at the very start of teardown, and everything below
			// borrows a pooled connection the closing context is about to invalidate. The
			// pollEvents() above is DB-free, so letting it run during shutdown is harmless
			// -
			// this single guard sits exactly where the risk (the connection pool) begins.
			if (shuttingDown) {
				return;
			}

			// Skip the debounced inventory while the watched tree is under any operation.
			// executionActive is a DB check; the in-memory lock (isBusy) closes the race
			// window where an organization already holds the tree but its Execution row
			// hasn't flipped to an active status yet. Skipping keeps inventoryPending, so
			// it simply retries next cycle - deferred, never dropped. (Periodic reconcile
			// now lives in the independent ReconcileScheduler, which defers on the same
			// lock.)
			boolean blocked = executionQueryService.active().isPresent() || isWatchFolderBusy();

			launchPendingInventory(blocked);
		} catch (Exception exception) {
			// A cycle that was interrupted or that raced the closing connection pool during
			// shutdown is expected, not a failure - log it quietly so teardown stays clean.
			if (shuttingDown || Thread.currentThread().isInterrupted()) {
				log.debug("Inventory watcher poll stopped during shutdown", exception);
			} else {
				log.error("Inventory watcher polling failed", exception);
			}
		}
	}

	private boolean isWatchFolderBusy() {
		String folder = status.get().folder();

		if (folder == null || folder.isBlank()) {
			return false;
		}

		return operationLockService.isBusy(Path.of(folder).toAbsolutePath().normalize());
	}

	/**
	 * Synchronized on the same monitor as {@link #reconfigure()} (and,
	 * transitively, {@link #stopSource()}). Without this, {@code reconfigure()} -
	 * called from the web request thread when an admin changes the monitored folder
	 * - could swap {@code watcher} and call {@code close()} on it while this method
	 * (running on the scheduled poll thread) was mid-iteration reading from the
	 * very same watcher, since the previous null-check-then-use on the volatile
	 * field was not atomic with the read loop below. Serializing the two closes
	 * that
	 * window: either a full poll runs to completion before the swap, or it waits
	 * until the swap (and the old watcher's shutdown) is done.
	 */
	private synchronized void pollEvents() {
		FileChangeSource currentWatcher = watcher.get();

		if (currentWatcher == null) {
			return;
		}

		// The watcher already drops directories, symbolic links, junctions and .lnk
		// shortcuts (via PhysicalFilePolicy), so every path here is a physical file
		// change worth a debounced re-inventory. Deletes are reported too so the
		// reconcile removes them from the catalog.
		for (Path changed : currentWatcher.pollChangedFiles()) {
			lastEventMillis = System.currentTimeMillis();

			inventoryPending = true;

			status.set(new InventoryWatchStatus(status.get().folder(), true, true, LocalDateTime.now(clock), null,
					lastReconciliation, lastReconciliationRepaired));

			log.debug("File-system change detected: {}", changed);
		}

		if (currentWatcher.consumeOverflow()) {
			// The WatchService dropped events under load: force a debounced re-inventory
			// (which runs its own FILE_EVENT reconcile) so the catalog re-syncs promptly
			// instead of waiting for the independent reconcile scheduler's next pass.
			inventoryPending = true;

			log.debug("Watch overflow detected; requesting early re-inventory.");
		}
	}

	private void launchPendingInventory(boolean blocked) {
		if (!inventoryPending || System.currentTimeMillis() - lastEventMillis < DEBOUNCE_MILLIS || blocked) {
			return;
		}

		inventoryPending = false;

		automaticReconcile(ExecutionTrigger.FILE_EVENT);

		inventoryBatchLauncherService.launch(watchRequest(), ExecutionTrigger.FILE_EVENT);
	}

	private void automaticReconcile(ExecutionTrigger trigger) {
		Path source = Path.of(status.get().folder()).toAbsolutePath().normalize();

		OrganizationReconcileRequest request = new OrganizationReconcileRequest(status.get().folder(),
				appSettingService.booleanValue(SettingsConstants.WATCH_RECURSIVE, true),
				appSettingService.booleanValue(SettingsConstants.WATCH_INCLUDE_HIDDEN, false), Integer.MAX_VALUE);

		OrganizationReconcileResponse response = organizationReconcileService.reconcileAndApply(request);

		reconcileExecutionRecorder.recordIfRepaired(trigger, source, response);

		lastReconciliation = LocalDateTime.now(clock);
		lastReconciliationRepaired = response.renamed() + response.repairedPaths() + response.markedMissing();
	}

	private InventoryRequest watchRequest() {
		return new InventoryRequest(status.get().folder(),
				appSettingService.booleanValue(SettingsConstants.WATCH_RECURSIVE, true),
				appSettingService.booleanValue(SettingsConstants.WATCH_INCLUDE_HIDDEN, false),
				appSettingService.booleanValue(SettingsConstants.WATCH_CALCULATE_HASHES, true),
				appSettingService.booleanValue(SettingsConstants.WATCH_FORCE_ANALYSIS, false));
	}

	private synchronized void stopSource() {
		FileChangeSource current = watcher.get();

		watcher.set(null);

		if (current != null) {
			try {
				current.close();
			} catch (IOException exception) {
				log.debug("Error closing inventory watcher for {}", current.root(), exception);
			}
		}
	}

	/**
	 * Stops the poll loop at the very start of context shutdown, before any bean is
	 * destroyed. {@link ContextClosedEvent} is published ahead of bean destruction,
	 * so handling it guarantees the scheduled thread is gone before the
	 * EntityManagerFactory and the Hikari DataSource close - it can never run a
	 * query against a pool that is being invalidated. Funnels into the idempotent
	 * {@link #stop()}.
	 */
	@EventListener(ContextClosedEvent.class)
	public void onContextClosed() {
		stop();
	}

	/**
	 * Idempotent graceful shutdown of the poll scheduler. Flips
	 * {@code shuttingDown} first so any in-flight cycle bails before touching the
	 * DB, cancels the scheduled task (interrupting a blocked cycle), then
	 * {@code shutdown()} and a short, bounded {@code awaitTermination} for the
	 * interrupted cycle to unwind - forcing {@code shutdownNow()} only if it
	 * overruns the window or this thread is itself interrupted. Not
	 * {@code synchronized} on this instance on purpose: {@code stopSource()}
	 * briefly takes that monitor, but holding it across {@code awaitTermination}
	 * would block the poll thread's own {@code pollEvents()} and stall the drain.
	 * Also invoked via {@code @PreDestroy} as a fallback when the bean is destroyed
	 * without a context-close event.
	 */
	@PreDestroy
	public void stop() {
		if (!stopped.compareAndSet(false, true)) {
			return;
		}

		shuttingDown = true;

		ScheduledFuture<?> task = pollTask.get();

		if (task != null) {
			task.cancel(true);
		}

		stopSource();

		// Graceful first: the cancel(true) above already interrupted any in-flight
		// cycle,
		// so shutdown() lets it unwind against the still-open pool. Only force with
		// shutdownNow() if it overruns the short, bounded drain window.
		executor.shutdown();

		try {
			if (!executor.awaitTermination(SHUTDOWN_DRAIN_MILLIS, TimeUnit.MILLISECONDS)) {
				executor.shutdownNow();
			}
		} catch (InterruptedException _) {
			executor.shutdownNow();

			Thread.currentThread().interrupt();
		}
	}
}