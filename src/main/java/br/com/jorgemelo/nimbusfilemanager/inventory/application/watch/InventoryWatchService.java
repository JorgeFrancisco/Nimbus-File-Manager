package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationPathKey;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.InventoryLauncherService;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryRequest;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryWatchStatus;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.FileChangeSource;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.FileChangeSourceFactory;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.WatchRecoveryReason;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.application.BackgroundWorkGate;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.InventoryWatchProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@Profile(NimbusProfiles.APP)
public class InventoryWatchService extends LocalizedComponent {

	private static final long POLL_MILLIS = 500;
	private static final long DEBOUNCE_MILLIS = 2_000;
	private static final long SHUTDOWN_DRAIN_MILLIS = 2_000;

	private final AppSettingService appSettingService;
	private final InventoryLauncherService inventoryLauncherService;
	private final ExecutionQueryService executionQueryService;
	private final ExecutionEnqueueService executionEnqueueService;
	private final ExecutionRepository executionRepository;
	private final OperationLockService operationLockService;
	private final FileChangeSourceFactory fileChangeSourceFactory;
	private final BackgroundWorkGate backgroundWorkGate;
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

	/**
	 * What the live source was built with, so a changed setting is noticed.
	 * Volatile because the poll thread reads it outside the monitor that writes
	 * it, to decide whether the live watch still matches the settings.
	 */
	private volatile boolean watchedRecursive;

	/**
	 * What asked for the pending inventory. Reported when it launches, because
	 * "why did this run again?" is a question the log could not answer: the
	 * changes are logged at debug, the launch at info, and diagnosing a watcher
	 * that fires too often meant guessing at which of the two paths woke it.
	 */
	private volatile String pendingReason = "a reconfiguration of the watched folder";

	/**
	 * The inventory that took responsibility for the backlog the USN catch-up
	 * recovered, and how many changes that was. Null while nothing is waiting on a
	 * scan, which is the ordinary state: a backlog exists only for the adoption
	 * that produced it.
	 */
	private volatile UUID offlineBacklogCoveredBy;
	private volatile int offlineBacklogSize;

	private volatile boolean shuttingDown;
	private final AtomicReference<ScheduledFuture<?>> pollTask = new AtomicReference<>();
	private final AtomicBoolean stopped = new AtomicBoolean();

	@Autowired
	public InventoryWatchService(AppSettingService appSettingService,
			InventoryLauncherService inventoryLauncherService, ExecutionQueryService executionQueryService,
			ExecutionEnqueueService executionEnqueueService, ExecutionRepository executionRepository,
			OperationLockService operationLockService, FileChangeSourceFactory fileChangeSourceFactory, Clock clock,
			InventoryWatchProperties watchProperties, BackgroundWorkGate backgroundWorkGate) {
		this.appSettingService = appSettingService;
		this.inventoryLauncherService = inventoryLauncherService;
		this.executionQueryService = executionQueryService;
		this.executionEnqueueService = executionEnqueueService;
		this.executionRepository = executionRepository;
		this.operationLockService = operationLockService;
		this.fileChangeSourceFactory = fileChangeSourceFactory;
		this.backgroundWorkGate = backgroundWorkGate;
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

	/**
	 * The startup adoption, which is a race this class has to win only once.
	 *
	 * <p>
	 * The poll thread starts half a second after this bean is built and the
	 * context takes tens of seconds more to be ready, so in practice the poll
	 * always gets here first, notices the configured folder and adopts it. This
	 * used to adopt it a second time regardless - closing a working handle,
	 * reopening it and running a second USN catch-up - and then ask for an
	 * inventory the first adoption had already asked for. It now launches only
	 * when it was the one that adopted, which is the case when the context became
	 * ready before the first poll.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public synchronized void startConfiguredMonitor() {
		if (reconfigure() && status.get().running()) {
			log.info("Launching startup inventory for configured folder {}", status.get().folder());

			absorbOfflineBacklog(inventoryLauncherService.launch(watchRequest(), ExecutionTrigger.MANUAL));
		}
	}

	/** @return whether this call is what started watching the configured folder */
	public synchronized boolean reconfigure() {
		return adopt();
	}

	/**
	 * The same work, private, because the poll thread reaches it and the
	 * constructor is what schedules that thread: handing a scheduler a reference a
	 * subclass could replace is the one thing a constructor must not do.
	 */
	private synchronized boolean adopt() {
		String configuredFolder = appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "");

		if (configuredFolder.isBlank()) {
			stopSource();

			status.set(InventoryWatchStatus.unconfigured());

			return false;
		}

		Path folder = Path.of(configuredFolder).toAbsolutePath().normalize();

		boolean recursive = appSettingService.booleanValue(SettingsConstants.WATCH_RECURSIVE, true);

		if (alreadyWatching(folder, recursive)) {
			return false;
		}

		stopSource();

		if (!Files.isDirectory(folder)) {
			status.set(new InventoryWatchStatus(folder.toString(), true, false, null,
					message("backend.watch.folderMissing"), null, 0));

			return false;
		}

		try {
			watcher.set(fileChangeSourceFactory.create(folder, recursive));
			watchedRecursive = recursive;

			status.set(new InventoryWatchStatus(folder.toString(), true, true, null, null, null, 0));

			log.info("Inventory watcher is monitoring {}", folder);

			return true;
		} catch (Exception exception) {
			status.set(new InventoryWatchStatus(folder.toString(), true, false, null, exception.getMessage(),
					null, 0));

			log.error("Could not start inventory watcher for {}", folder, exception);

			return false;
		}
	}

	/**
	 * Whether the live source already covers exactly what the settings ask for.
	 *
	 * <p>
	 * Read under the same monitor that writes it, which is what makes this
	 * idempotence rather than a check somebody can lose a race against: every
	 * caller - the poll thread, the request thread that saves a setting, and the
	 * {@code ApplicationReadyEvent} - reaches it through {@link #adopt()}, and one
	 * of them holds the lock at a time. Whoever arrives second sees the source the
	 * first one installed.
	 *
	 * <p>
	 * The recursion flag takes part because it is what the source was built with:
	 * the same folder watched shallowly is not the same watch.
	 */
	private boolean alreadyWatching(Path folder, boolean recursive) {
		FileChangeSource current = watcher.get();

		return current != null && recursive == watchedRecursive && status.get().running()
				&& current.root().equals(folder);
	}

	/**
	 * What saving a watch setting asks for. The inventory is launched whether or
	 * not the folder changed: somebody pressed save, and answering an explicit
	 * request with silence because the value happened to be the same one is not
	 * the same thing as avoiding the duplicated startup adoption.
	 */
	public void reconfigureAndInventory() {
		adoptAndInventory();
	}

	/**
	 * The same work, reachable from the poll thread without going through a public
	 * method: the constructor schedules that poll, and handing a scheduler a
	 * reference that a subclass could replace is the one thing a constructor must
	 * not do.
	 */
	private synchronized void adoptAndInventory() {
		adopt();

		if (!status.get().running()) {
			return;
		}

		// Nothing to hand over in this branch: the pending queued here asks for a
		// reconcile and a full inventory of the same tree, which is more than the
		// backlog needs, and the poll that drains it will find the work already
		// asked for.
		if (executionQueryService.active().isPresent()) {
			inventoryPending = true;

			lastEventMillis = 0;

			log.info("Inventory queued after monitor reconfiguration for folder {}", status.get().folder());

			return;
		}

		log.info("Launching inventory after monitor reconfiguration for folder {}", status.get().folder());

		absorbOfflineBacklog(inventoryLauncherService.launch(watchRequest(), ExecutionTrigger.MANUAL));
	}

	/**
	 * Hands the offline backlog to the full inventory this adoption has just
	 * asked for, when that inventory is one that can be said to cover it.
	 *
	 * <p>
	 * This is the whole of the fix, and it is a decision made here rather than
	 * inferred later: the adoption is the one moment that knows both that a
	 * journal replay produced a list and that a walk of the same tree is about to
	 * start. Everything the replay reports happened before the walk begins, so the
	 * walk meets each of those files in whatever state the replay was telling us
	 * about - which is why a second pass over 146k files to catalogue nothing was
	 * pure waste. Live notifications never come this way: the directory handle is
	 * armed before the replay runs and its events arrive by the poll, so this
	 * cannot swallow a change made while the walk is in progress.
	 *
	 * <p>
	 * Two things stop the hand-over, both because the walk genuinely would not
	 * cover them. A path that is no longer on disk is a removal, and a walk finds
	 * nothing where nothing is - only a reconcile can retire that row, so a single
	 * one of them leaves the whole backlog to the conservative route. And an
	 * inventory that is already running may have walked past these paths before
	 * they changed, so only one that has not started yet is accepted.
	 *
	 * <p>
	 * The backlog still raises the pending it always did. What changes is that the
	 * pending is now retractable: {@link #offlineBacklogAbsorbed()} drops it when
	 * the inventory reports that it finished, and leaves it standing when it did
	 * not.
	 */
	private void absorbOfflineBacklog(ExecutionResponse inventory) {
		FileChangeSource source = watcher.get();

		if (source == null) {
			return;
		}

		List<Path> backlog = source.takeOfflineBacklog();

		if (backlog.isEmpty()) {
			return;
		}

		long gone = backlog.stream().filter(path -> !Files.exists(path)).count();

		String reason = backlog.size() + " offline change(s) recovered from the USN journal";

		if (gone > 0) {
			log.info("USN catch-up for {} recovered {} offline change(s), {} of them no longer on disk: a walk "
					+ "cannot see what is not there, so a reconciliation is queued for them",
					status.get().folder(), backlog.size(), gone);

			raiseUncoveredPending(reason);

			return;
		}

		if (!ExecutionStatus.PENDING.name().equals(inventory.status())) {
			log.info("USN catch-up for {} recovered {} offline change(s), but inventory {} was already under way "
					+ "and may have walked past them: queuing the ordinary recovery",
					status.get().folder(), backlog.size(), inventory.executionId());

			raiseUncoveredPending(reason);

			return;
		}

		log.info("USN catch-up for {} recovered {} offline change(s); inventory {} walks the whole library and "
				+ "covers them", status.get().folder(), backlog.size(), inventory.executionId());

		// Written before the pending it belongs to, never after: the poll thread can
		// be looking at both while this runs on the one that handled the ready event,
		// and a pending seen without its cover reads as an ordinary one - which costs
		// the redundant pass this exists to avoid.
		offlineBacklogCoveredBy = inventory.executionId();
		offlineBacklogSize = backlog.size();

		raisePending(reason);
	}

	/**
	 * Read from the last finished RECONCILE rather than remembered from the last
	 * one this instance ran. The pass happens in the worker now, so there is
	 * nothing for this process to remember - and the answer got truer on the way:
	 * the label says "last reconciliation", and it now means the last one,
	 * whether a file event or the scheduler asked for it, instead of only the ones
	 * this watcher happened to trigger.
	 */
	public InventoryWatchStatus status() {
		InventoryWatchStatus current = status.get();

		return executionRepository
				.findFirstByExecutionTypeAndStatusOrderByFinishedAtDesc(ExecutionType.RECONCILE,
						ExecutionStatus.FINISHED)
				.map(reconcile -> new InventoryWatchStatus(current.folder(), current.configured(), current.running(),
						current.lastEvent(), current.error(), reconcile.getFinishedAt(), reconcile.getRepairedItems()))
				.orElse(current);
	}

	public synchronized void pause() {
		stopSource();

		inventoryPending = false;

		InventoryWatchStatus current = status.get();

		status.set(new InventoryWatchStatus(current.folder(), current.configured(), false, current.lastEvent(),
				message("backend.watch.switchingLibrary"), null, 0));
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
			// The gate adds the other transient state this loop cannot see for itself:
			// while a restore replaces the catalog, the table about to be queried may
			// not exist for another minute.
			if (shuttingDown || backgroundWorkGate.standDown()) {
				return;
			}

			// Skip the debounced inventory while the watched tree is under any operation.
			// executionActive is a DB check; the in-memory lock (isBusy) closes the race
			// window where an organization already holds the tree but its Execution row
			// hasn't flipped to an active status yet. Skipping keeps inventoryPending, so
			// it simply retries next cycle - deferred, never dropped. (Periodic reconcile
			// now lives in the independent ReconcileScheduler, which defers on the same
			// lock.)
			if (adoptConfiguredLibrary()) {
				return;
			}

			boolean blocked = executionQueryService.active().isPresent() || isWatchFolderBusy();

			launchPendingInventory(blocked);
		} catch (Exception exception) {
			// A cycle that was interrupted or that raced the closing connection pool during
			// shutdown is expected, not a failure - log it quietly so teardown stays clean.
			if (shuttingDown || backgroundWorkGate.standDown() || Thread.currentThread().isInterrupted()) {
				log.debug("Inventory watcher poll stopped while the application was busy elsewhere", exception);
			} else {
				log.error("Inventory watcher polling failed", exception);
			}
		}
	}

	/**
	 * Follows the library the setting names, whoever changed it.
	 *
	 * <p>
	 * The switch is a worker's job now, and the worker writes the setting in
	 * another process - so this loop is what makes the watcher of this process
	 * catch up. It also closes a gap that predates the change: the folder was only
	 * ever re-read when somebody called {@code reconfigure} from a request thread,
	 * which meant a setting changed by anything else went unnoticed until restart.
	 *
	 * <p>
	 * Comparing the normalized paths rather than the raw strings, because
	 * {@code reconfigure} stores the normalized form and a needless reconfigure
	 * would tear down a working watcher every cycle.
	 *
	 * <p>
	 * What is compared is folder <em>and</em> depth, the same identity
	 * {@link #alreadyWatching} uses, and this half is prevention rather than
	 * repair: every writer of the depth setting today calls a reconfigure of its
	 * own, so nothing in the product reaches this branch. It is here because the
	 * folder already changes from another process - the library switch runs in the
	 * worker - and the day the depth follows it, a poll that compared only the
	 * folder would go on watching the old depth with nothing to say so.
	 *
	 * @return whether the library changed, in which case this cycle ends - the
	 * inventory of the new one has just been asked for and there is nothing left to
	 * poll for the old
	 */
	private boolean adoptConfiguredLibrary() {
		String configured = appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "");
		String watched = status.get().folder();

		if (configured.isBlank() || (sameFolder(configured, watched) && sameDepth())) {
			return false;
		}

		log.info("The monitored library became {} (recursive={}); the watcher was following {} (recursive={})",
				configured, appSettingService.booleanValue(SettingsConstants.WATCH_RECURSIVE, true), watched,
				watchedRecursive);

		adoptAndInventory();

		return true;
	}

	/**
	 * Whether the live source was built with the depth the setting now asks for.
	 *
	 * <p>
	 * Answered yes when nothing is live: the depth of a source that does not exist
	 * is not a difference to chase, and treating it as one would have this loop
	 * rebuild a folder that failed to open twice a second. Reopening after a
	 * failure stays what it was - somebody saving the setting again.
	 */
	private boolean sameDepth() {
		if (watcher.get() == null || !status.get().running()) {
			return true;
		}

		return watchedRecursive == appSettingService.booleanValue(SettingsConstants.WATCH_RECURSIVE, true);
	}

	private boolean sameFolder(String configured, String watched) {
		if (watched == null || watched.isBlank()) {
			return false;
		}

		return PathUtils.normalizePath(configured).equals(PathUtils.normalizePath(watched));
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
	 * that window: either a full poll runs to completion before the swap, or it
	 * waits until the swap (and the old watcher's shutdown) is done.
	 */
	private synchronized void pollEvents() {
		FileChangeSource currentWatcher = watcher.get();

		if (currentWatcher == null) {
			return;
		}

		// Every path a source reports is worth the debounced pass, and none of them
		// is screened here on purpose. What arrives is not only files: the Windows
		// sources report directories too, and they have to, because a folder that
		// appears already full is the only notice its files ever produce - the
		// reconcile retires what left, it does not catalogue what arrived. Deletes
		// come through for the opposite reason, so that reconcile can retire them.
		// Screening this list by PhysicalFilePolicy, which reads as the obvious
		// tidying, is what would drop those folders.
		for (Path changed : currentWatcher.pollChangedFiles()) {
			lastEventMillis = System.currentTimeMillis();

			raiseUncoveredPending(changed.toString());

			status.set(new InventoryWatchStatus(status.get().folder(), true, true, LocalDateTime.now(clock), null,
					null, 0));

			log.debug("File-system change detected: {}", changed);
		}

		currentWatcher.consumeRecoveryReason().ifPresent(this::requestRecovery);
	}

	/**
	 * Forces the debounced re-inventory the source asked for, saying which of the
	 * three situations it was.
	 *
	 * <p>
	 * The distinction is not decoration. Two of these are ordinary - a journal
	 * cursor too old to replay is what any long run leaves behind - and one means
	 * the operating system dropped notifications this process will never see. They
	 * were one boolean called {@code overflow}, so every start of the application
	 * announced lost events that had not been lost.
	 */
	private void requestRecovery(WatchRecoveryReason reason) {
		raiseUncoveredPending(describe(reason));
	}

	/**
	 * Asks for the pass and withdraws any promise that a running scan answers for
	 * it - the safety half of this slice.
	 *
	 * <p>
	 * What arrives here is either a live notification or a source saying it cannot
	 * account for its window, and a walk that started before either one covers
	 * neither. So whatever an adoption undertook a moment ago stops being an
	 * answer and the pending goes back to being unconditional. Withdrawn before
	 * the pending is raised, for the same reason it is granted after: whoever sees
	 * the pending must not see a cover that no longer holds.
	 */
	private void raiseUncoveredPending(String reason) {
		offlineBacklogCoveredBy = null;

		raisePending(reason);
	}

	/**
	 * Keeps the first reason given rather than the last: the one that woke the
	 * watcher explains the run better than whichever change happened to arrive
	 * just before it fired.
	 */
	private void raisePending(String reason) {
		if (!inventoryPending) {
			pendingReason = reason;
		}

		inventoryPending = true;
	}

	private static String describe(WatchRecoveryReason reason) {
		return switch (reason) {
		case EVENTS_LOST -> "the change source lost file events";
		case JOURNAL_UNREPLAYABLE -> "the USN journal could not be replayed from the stored cursor";
		case JOURNAL_REPLAY_INCOMPLETE -> "the USN replay could not account for every record";
		};
	}

	private void launchPendingInventory(boolean blocked) {
		if (!inventoryPending || System.currentTimeMillis() - lastEventMillis < DEBOUNCE_MILLIS || blocked) {
			return;
		}

		if (offlineBacklogAbsorbed()) {
			return;
		}

		inventoryPending = false;

		log.info("Re-inventorying because of: {}", pendingReason);

		try {
			automaticReconcile(ExecutionTrigger.FILE_EVENT);

			inventoryLauncherService.launch(watchRequest(), ExecutionTrigger.FILE_EVENT);
		} catch (RuntimeException failure) {
			// Whatever refused the queue - a connection that blinked, a transaction
			// that could not commit - the change on disk is still uncatalogued, and
			// nothing else goes looking for it: the reconcile retires what left, it
			// never catalogues what arrived. So the pending goes back and the next
			// cycle asks again. Raised rather than assigned, so a reason that
			// arrived meanwhile keeps its place, and rethrown so the poll logs it.
			raisePending(pendingReason);

			throw failure;
		}
	}

	/**
	 * Whether the pending about to fire is only the offline backlog, and the
	 * inventory that promised to cover it did.
	 *
	 * <p>
	 * Asked here rather than when the promise was made, because here is where the
	 * answer exists. Reaching this line means the pass is no longer blocked, and
	 * the pass is blocked for as long as any execution is active - so the
	 * inventory has already reached a status that will not change again, and there
	 * is no window in which it could be read half-done.
	 *
	 * <p>
	 * Only {@code FINISHED} counts. A walk that was cancelled, rejected for the
	 * lock or that failed never reached the files, and one that finished with
	 * errors reached some file it could not write - without knowing which, the
	 * backlog has to be treated as uncovered. In every one of those the answer is
	 * the behaviour that predates this slice: the reconcile and the inventory run.
	 * An execution that cannot be found at all is read the same conservative way.
	 */
	private boolean offlineBacklogAbsorbed() {
		UUID covering = offlineBacklogCoveredBy;

		if (covering == null) {
			return false;
		}

		offlineBacklogCoveredBy = null;

		ExecutionStatus covered = executionRepository.findByPublicId(covering).map(Execution::getStatus).orElse(null);

		if (covered != ExecutionStatus.FINISHED) {
			log.info("The inventory that was to cover {} offline change(s) ended as {}: recovering them the "
					+ "ordinary way", offlineBacklogSize, covered);

			return false;
		}

		inventoryPending = false;

		log.info("The initial inventory covered the {} offline change(s) the USN catch-up recovered; no further "
				+ "pass is needed", offlineBacklogSize);

		return true;
	}

	/**
	 * Queued, never run here - the same pass the scheduler queues, asked for by a
	 * file event instead of by the clock. Walking the tree and reading every
	 * catalogued location under it is worker work, and doing it on the watch
	 * thread was what made a burst of changes hold the poll loop while the catalog
	 * was read end to end.
	 *
	 * <p>
	 * Repeated events do not pile up: the dedup key is the folder, so at most one
	 * RECONCILE waits and one runs for it, and every event that arrives while
	 * those two exist finds the work already asked for. That is the debounce the
	 * queue provides on top of the one this class already applies in time.
	 */
	private void automaticReconcile(ExecutionTrigger trigger) {
		Path source = Path.of(status.get().folder()).toAbsolutePath().normalize();

		executionEnqueueService.enqueue(Execution.builder().executionType(ExecutionType.RECONCILE)
				.triggerEvent(trigger).sourcePath(source.toString())
				.recursive(appSettingService.booleanValue(SettingsConstants.WATCH_RECURSIVE, true))
				.executeFlag(true).dedupKey(OperationPathKey.canonical(source))
				.statusMessage(StatusMessage.code(ExecutionMessages.RECONCILE_REPAIRED)).build());
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

		// A promise about a backlog belongs to the source that recovered it and to
		// the library it was watching. Neither outlives the source, so switching
		// library - or the same one watched to a different depth, which is also a new
		// source - starts with nothing owed.
		offlineBacklogCoveredBy = null;

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