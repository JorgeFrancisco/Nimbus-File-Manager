package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.InventoryLauncherService;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.FileSystemChange;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.FileChangeSourceFactory;
import br.com.jorgemelo.nimbusfilemanager.inventory.domain.enums.WatchRecoveryReason;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.application.BackgroundWorkGate;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.InventoryWatchProperties;

/**
 * What the watcher does with the changes the USN journal replay recovers when
 * the adoption that produced them is also starting a walk of the whole
 * library.
 *
 * <p>
 * The poll loop is switched off and each cycle is driven by hand, so every case
 * is a fixed sequence rather than a wait: what is being asserted here is which
 * work is asked for, and that answer must not depend on how loaded the machine
 * running the suite is. The scheduling itself is proved by
 * {@code InventoryWatchServiceTest}.
 *
 * <p>
 * Throughout, one call to {@code launch} is the startup inventory and a second
 * one is the redundant pass this slice exists to remove; an {@code enqueue} is
 * the reconcile that used to come with it.
 */
class OfflineBacklogAbsorptionTest {

	private final ScanExclusionService exclusions = mock(ScanExclusionService.class);
	private final ExecutionEnqueueService enqueueService = mock(ExecutionEnqueueService.class);
	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);
	private final InventoryLauncherService launcher = mock(InventoryLauncherService.class);
	private final ExecutionQueryService queries = mock(ExecutionQueryService.class);
	/**
	 * Asked, and answering that none of these were ours - which is what makes the
	 * filtering visible: a source that dropped a change anyway would be dropping it
	 * for some other reason.
	 */
	private final SelfWrittenPathRegistry pathRegistry = mock(SelfWrittenPathRegistry.class);

	private final AtomicReference<Optional<ExecutionResponse>> active = new AtomicReference<>(Optional.empty());
	private final AtomicReference<ExecutionStatus> launchStatus = new AtomicReference<>(ExecutionStatus.PENDING);
	private final AtomicReference<String> configuredFolder = new AtomicReference<>();
	private final AtomicBoolean configuredRecursive = new AtomicBoolean(true);
	private final List<RecordingFileChangeSource> built = new ArrayList<>();

	private List<FileSystemChange> nextBacklog = List.of();
	private WatchRecoveryReason nextReason;
	private UUID lastLaunched;

	@TempDir
	Path library;

	private InventoryWatchService service;

	@AfterEach
	void tearDown() {
		if (service != null) {
			service.stop();
		}
	}

	@Test
	void aFileThatArrivedWhileTheApplicationWasDownIsLeftToTheStartupInventory() throws Exception {
		startWatching(backlogOf("holiday.jpg"));

		inventoryIsRunning();
		poll();

		inventoryEnded(ExecutionStatus.FINISHED);
		poll();

		verify(launcher, times(1)).launch(any(), any());
		verify(enqueueService, never()).enqueue(any());
	}

	@Test
	void everyOfflineChangeIsAbsorbedAndNotJustTheFirstOne() throws Exception {
		startWatching(backlogOf("a.jpg", "b.jpg", "c.jpg", "d.jpg", "e.jpg"));

		inventoryIsRunning();
		poll();

		inventoryEnded(ExecutionStatus.FINISHED);
		poll();

		verify(launcher, times(1)).launch(any(), any());
		verify(enqueueService, never()).enqueue(any());
	}

	/**
	 * The scenario the absorption must never break: the walk has already passed a
	 * folder when a file is created in it. Nothing about the scan covers that
	 * file, and the only thing that will catalogue it is the pass this pending
	 * asks for.
	 */
	@Test
	void aChangeMadeWhileTheScanWalksIsNotAbsorbedByIt() throws Exception {
		startWatching(List.of());

		inventoryIsRunning();

		lastBuilt().reportLive(library.resolve("born-mid-scan.jpg"));

		poll();

		inventoryEnded(ExecutionStatus.FINISHED);
		poll();

		verify(launcher, times(2)).launch(any(), any());
		verify(enqueueService, times(1)).enqueue(any());
	}

	/**
	 * Both kinds at once, which is the case that decides whether the mechanism
	 * really distinguishes them: the offline ones go to the scan and the live one
	 * survives it, leaving exactly one further pass rather than none or two.
	 */
	@Test
	void aLiveChangeSurvivesWhileTheOfflineOnesAreAbsorbed() throws Exception {
		startWatching(backlogOf("was-here-before.jpg"));

		inventoryIsRunning();

		lastBuilt().reportLive(library.resolve("born-mid-scan.jpg"));

		poll();

		inventoryEnded(ExecutionStatus.FINISHED);
		poll();

		verify(launcher, times(2)).launch(any(), any());
		verify(enqueueService, times(1)).enqueue(any());
	}

	@Test
	void anInventoryThatFailedCoversNothing() throws Exception {
		startWatching(backlogOf("holiday.jpg"));

		inventoryIsRunning();
		poll();

		inventoryEnded(ExecutionStatus.ERROR);
		poll();

		verify(launcher, times(2)).launch(any(), any());
		verify(enqueueService, times(1)).enqueue(any());
	}

	@Test
	void anInventoryThatWasCancelledCoversNothing() throws Exception {
		startWatching(backlogOf("holiday.jpg"));

		inventoryIsRunning();
		poll();

		inventoryEnded(ExecutionStatus.CANCELLED);
		poll();

		verify(launcher, times(2)).launch(any(), any());
		verify(enqueueService, times(1)).enqueue(any());
	}

	/**
	 * A walk that met a file it could not write finished, but not over
	 * everything, and there is no way from here to tell whether the file it lost
	 * was one of these. Read as uncovered, which costs a pass and never a
	 * catalogue entry.
	 */
	@Test
	void anInventoryThatFinishedWithErrorsCoversNothing() throws Exception {
		startWatching(backlogOf("holiday.jpg"));

		inventoryIsRunning();
		poll();

		inventoryEnded(ExecutionStatus.FINISHED_WITH_ERRORS);
		poll();

		verify(launcher, times(2)).launch(any(), any());
		verify(enqueueService, times(1)).enqueue(any());
	}

	@Test
	void anExecutionThatCannotBeFoundIsReadAsUncovered() throws Exception {
		startWatching(backlogOf("holiday.jpg"));

		inventoryIsRunning();
		poll();

		when(executionRepository.findByExecutionPublicId(lastLaunched)).thenReturn(Optional.empty());

		active.set(Optional.empty());

		poll();

		verify(launcher, times(2)).launch(any(), any());
		verify(enqueueService, times(1)).enqueue(any());
	}

	@Test
	void aJournalThatCouldNotBeReplayedKeepsTheConservativeRecovery() throws Exception {
		nextReason = WatchRecoveryReason.JOURNAL_UNREPLAYABLE;

		startWatching(List.of());

		inventoryIsRunning();
		poll();

		inventoryEnded(ExecutionStatus.FINISHED);
		poll();

		verify(launcher, times(2)).launch(any(), any());
		verify(enqueueService, times(1)).enqueue(any());
	}

	/**
	 * The replay handed over paths and, in the same breath, said it could not
	 * account for every record in its window. The paths are real but the list is
	 * not the whole story, so the recovery outranks the absorption - otherwise
	 * the optimisation would be reading a partial answer as a complete one.
	 */
	@Test
	void anIncompleteReplayOutranksTheAbsorptionEvenWithPathsInHand() throws Exception {
		nextReason = WatchRecoveryReason.JOURNAL_REPLAY_INCOMPLETE;

		startWatching(backlogOf("holiday.jpg"));

		inventoryIsRunning();
		poll();

		inventoryEnded(ExecutionStatus.FINISHED);
		poll();

		verify(launcher, times(2)).launch(any(), any());
		verify(enqueueService, times(1)).enqueue(any());
	}

	@Test
	void eventsLostByTheLiveSourceStillForceRecovery() throws Exception {
		nextReason = WatchRecoveryReason.EVENTS_LOST;

		startWatching(backlogOf("holiday.jpg"));

		inventoryIsRunning();
		poll();

		inventoryEnded(ExecutionStatus.FINISHED);
		poll();

		verify(launcher, times(2)).launch(any(), any());
		verify(enqueueService, times(1)).enqueue(any());
	}

	/**
	 * A path the replay reported and that is no longer on disk was removed or
	 * renamed away. A walk finds nothing where nothing is, so it cannot be what
	 * retires the catalogue row - only the reconcile can, and one such path is
	 * enough to send the whole backlog the conservative way.
	 */
	@Test
	void aPathThatIsNoLongerOnDiskCannotBeCoveredByAWalk() throws Exception {
		List<FileSystemChange> backlog = new ArrayList<>(backlogOf("still-here.jpg"));

		// Deleted while nothing was watching: the source reports the path and nothing
		// more, which is exactly what makes a walk unable to settle it.
		backlog.add(Changes.deleted(library.resolve("deleted-while-down.jpg")));

		startWatching(backlog);

		inventoryIsRunning();
		poll();

		inventoryEnded(ExecutionStatus.FINISHED);
		poll();

		verify(launcher, times(2)).launch(any(), any());
		verify(enqueueService, times(1)).enqueue(any());
	}

	/**
	 * An inventory that was already under way when the source was built may have
	 * walked past these paths before they changed, so it is no answer for them.
	 */
	@Test
	void anInventoryAlreadyUnderWayIsNotAcceptedAsCover() throws Exception {
		launchStatus.set(ExecutionStatus.RUNNING);

		startWatching(backlogOf("holiday.jpg"));

		inventoryIsRunning();
		poll();

		inventoryEnded(ExecutionStatus.FINISHED);
		poll();

		verify(launcher, times(2)).launch(any(), any());
		verify(enqueueService, times(1)).enqueue(any());
	}

	@Test
	void withNothingRecoveredOfflineTheStartupBehavesAsItAlwaysDid() throws Exception {
		startWatching(List.of());

		inventoryIsRunning();
		poll();

		inventoryEnded(ExecutionStatus.FINISHED);
		poll();

		verify(launcher, times(1)).launch(any(), any());
		verify(enqueueService, never()).enqueue(any());
	}

	/**
	 * The promise belongs to the source that made it. Switching library builds a
	 * new source with a backlog of its own, and it is that one the new scan
	 * answers for.
	 *
	 * <p>
	 * Nothing owed from the old library is what the assertion actually rests on:
	 * had the first library's promise survived the switch, the decision at the end
	 * would look up an execution that never finished and queue the pair. Zero of
	 * them is only possible if the promise was replaced.
	 */
	@Test
	void switchingLibraryLeavesNothingOwedFromTheOldOne(@TempDir Path other) throws Exception {
		startWatching(backlogOf("holiday.jpg"));

		configuredFolder.set(other.toString());

		nextBacklog = backlogIn(other, "moved-in.jpg");

		poll();

		assertThat(built).as("the new library is a new source").hasSize(2);

		inventoryIsRunning();
		poll();

		inventoryEnded(ExecutionStatus.FINISHED);
		poll();

		verify(launcher, times(2)).launch(any(), any());
		verify(enqueueService, never()).enqueue(any());
	}

	/**
	 * The same folder watched to a different depth is not the same watch, so it
	 * is a new source with a backlog of its own - and that backlog is absorbed by
	 * the inventory the new adoption launches, exactly like the first one.
	 *
	 * <p>
	 * Asked for through the settings screen rather than by polling, because that
	 * is the only way a depth change arrives: the poll re-reads which folder is
	 * configured, not how deeply it is watched.
	 */
	@Test
	void watchingTheSameFolderToADifferentDepthAbsorbsItsOwnBacklog() throws Exception {
		startWatching(backlogOf("holiday.jpg"));

		configuredRecursive.set(false);

		nextBacklog = backlogOf("shallow.jpg");

		service.reconfigureAndInventory();

		assertThat(built).as("a different depth is a different watch").hasSize(2);

		inventoryIsRunning();
		poll();

		inventoryEnded(ExecutionStatus.FINISHED);
		poll();

		verify(launcher, times(2)).launch(any(), any());
		verify(enqueueService, never()).enqueue(any());
	}

	/**
	 * The adoption that happens in production is usually the poll thread's, not
	 * the {@code ApplicationReadyEvent}'s - the context takes tens of seconds to
	 * be ready and the poll starts half a second in. It absorbs the same way.
	 */
	@Test
	void theBacklogIsAbsorbedWhenThePollThreadIsWhatAdopts() throws Exception {
		nextBacklog = backlogOf("holiday.jpg");

		service = watchService();

		poll();

		inventoryIsRunning();
		poll();

		inventoryEnded(ExecutionStatus.FINISHED);
		poll();

		verify(launcher, times(1)).launch(any(), any());
		verify(enqueueService, never()).enqueue(any());
	}

	/**
	 * Builds the service, adopts the library and runs the startup inventory,
	 * leaving the backlog in whatever state the case under test needs.
	 */
	private void startWatching(List<FileSystemChange> backlog) {
		nextBacklog = backlog;

		service = watchService();

		service.startConfiguredMonitor();
	}

	private InventoryWatchService watchService() {
		configuredFolder.compareAndSet(null, library.toString());

		when(queries.active()).thenAnswer(_ -> active.get());
		when(launcher.launch(any(), any())).thenAnswer(_ -> queued(launchStatus.get()));

		return new InventoryWatchService(settings(), launcher, queries, enqueueService, executionRepository,
				mock(OperationLockService.class), sourceFactory(), Clock.systemDefaultZone(), watchProps(),
				new BackgroundWorkGate(), recognisesNothing());
	}

	/**
	 * The provider hands back a source the test fills, which is what stands in
	 * for the Windows one: the portable {@code WatchService} has no backlog to
	 * absorb, so it could not exercise any of this.
	 */
	private FileChangeSourceFactory sourceFactory() {
		return new FileChangeSourceFactory(root -> {
			RecordingFileChangeSource created = new RecordingFileChangeSource(root, nextBacklog, nextReason);

			built.add(created);

			return Optional.of(created);
		}, pathRegistry, exclusions);
	}

	private AppSettingService settings() {
		AppSettingService settings = mock(AppSettingService.class);

		when(settings.stringValue(SettingsConstants.WATCH_FOLDER, "")).thenAnswer(_ -> configuredFolder.get());
		when(settings.booleanValue(SettingsConstants.WATCH_RECURSIVE, true))
				.thenAnswer(_ -> configuredRecursive.get());
		when(settings.booleanValue(SettingsConstants.WATCH_INCLUDE_HIDDEN, false)).thenReturn(false);
		when(settings.booleanValue(SettingsConstants.WATCH_CALCULATE_HASHES, true)).thenReturn(true);
		when(settings.booleanValue(SettingsConstants.WATCH_FORCE_ANALYSIS, false)).thenReturn(false);

		return settings;
	}

	/** Disabled, so the only cycles that run are the ones a test asks for. */
	private InventoryWatchProperties watchProps() {
		InventoryWatchProperties properties = new InventoryWatchProperties();

		properties.setEnabled(false);

		return properties;
	}

	/** Real files, because a walk can only cover a path that is there to walk. */
	private List<FileSystemChange> backlogOf(String... names) throws IOException {
		return backlogIn(library, names);
	}

	/**
	 * What the source reports having missed while nothing was watching. Changes
	 * rather than paths: a backlog is a list of things that happened, and a source
	 * that cannot say what happened to a path says so by leaving the rest of the
	 * change empty rather than by handing back a bare path.
	 */
	private List<FileSystemChange> backlogIn(Path folder, String... names) throws IOException {
		List<FileSystemChange> changes = new ArrayList<>();

		for (String name : names) {
			changes.add(Changes.created(Files.writeString(folder.resolve(name), name)));
		}

		return changes;
	}

	private RecordingFileChangeSource lastBuilt() {
		return built.get(built.size() - 1);
	}

	/**
	 * Something is holding the tree, which is what the launched inventory does
	 * for as long as it walks. Deliberately not the launched one itself: what
	 * blocks the watcher is any active execution, and the test must not depend on
	 * it being this one.
	 */
	private void inventoryIsRunning() {
		active.set(Optional.of(response(UUID.randomUUID(), ExecutionStatus.RUNNING)));
	}

	/**
	 * The launched inventory reaches its final status and stops being what blocks
	 * the watcher, which together are what let the next cycle decide.
	 */
	private void inventoryEnded(ExecutionStatus status) {
		when(executionRepository.findByExecutionPublicId(lastLaunched))
				.thenReturn(Optional.of(Execution.builder().status(status).build()));

		active.set(Optional.empty());
	}

	private ExecutionResponse queued(ExecutionStatus status) {
		lastLaunched = UUID.randomUUID();

		return response(lastLaunched, status);
	}

	private ExecutionResponse response(UUID id, ExecutionStatus status) {
		return new ExecutionResponse(id, ExecutionType.INVENTORY.name(), status.name(), LocalDateTime.now(), null,
				configuredFolder.get(), null, 0, 0, 0, 0, 0, 0, null, null, null, true);
	}

	/**
	 * One cycle of the loop the scheduler would be running, plus the reset of the
	 * debounce clock: a live change stamps it, and waiting two seconds per test
	 * would prove nothing this class is about.
	 */
	private void poll() throws Exception {
		setLastEventMillis();

		Method pollSafely = InventoryWatchService.class.getDeclaredMethod("pollSafely");

		pollSafely.setAccessible(true);
		pollSafely.invoke(service);
	}

	private void setLastEventMillis() throws Exception {
		Field field = InventoryWatchService.class.getDeclaredField("lastEventMillis");

		field.setAccessible(true);
		field.setLong(service, 0L);
	}

	/**
	 * A recognition that recognises nothing, which is what these tests are about:
	 * they assert when the debounced pass runs and what wakes it, and a change the
	 * catalog could account for by itself never reaches that pass. Handing over a
	 * recognition that always declines keeps every one of them asking the question
	 * it was written to ask.
	 */
	private FileChangeRecognition recognisesNothing() {
		FileChangeRecognition recognition = mock(FileChangeRecognition.class);

		lenient().when(recognition.recognise(any())).thenReturn(false);

		return recognition;
	}
}