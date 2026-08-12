package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.constants.GeoMessages;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.GeoDatasetPayload;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LocationRebuildPayload;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LocationRebuildResult;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationRebuildScope;
import br.com.jorgemelo.nimbusfilemanager.shared.application.BackgroundWorkGate;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * The two geographic workloads as intentions and as runs.
 *
 * <p>
 * What the set has to get right is that both name the same folder - which is the
 * whole of the exclusion between them, and the reason they migrated together -
 * that a scope asked for is the scope that runs, and that a dataset update
 * refuses a payload it does not understand before touching anything.
 */
class GeoQueueTest {

	@TempDir
	private Path workspace;

	private final ExecutionEnqueueService executionEnqueueService = mock(ExecutionEnqueueService.class);
	private final ExecutionPayloadCodec executionPayloadCodec = new ExecutionPayloadCodec(new ObjectMapper());
	private final BackgroundWorkGate backgroundWorkGate = mock(BackgroundWorkGate.class);
	private final WorkspaceManager workspaceManager = mock(WorkspaceManager.class);

	private final LocationRebuildService locationRebuildService = mock(LocationRebuildService.class);
	private final OfflineGeoDataset offlineGeoDataset = mock(OfflineGeoDataset.class);
	private final MediaLocationService mediaLocationService = mock(MediaLocationService.class);
	private final GeoDatasetProgress geoDatasetProgress = mock(GeoDatasetProgress.class);
	private final ExecutionProgressService executionProgressService = mock(ExecutionProgressService.class);
	private final ExecutionCancellationService executionCancellationService = mock(ExecutionCancellationService.class);
	private final InventoryRunningState inventoryRunningState = mock(InventoryRunningState.class);

	private GeoLauncher launcher;

	private final LocationRebuildJobHandler rebuildHandler = new LocationRebuildJobHandler(locationRebuildService,
			executionProgressService, executionCancellationService, executionPayloadCodec, inventoryRunningState);

	private final GeoDatasetJobHandler datasetHandler = new GeoDatasetJobHandler(offlineGeoDataset,
			mediaLocationService, geoDatasetProgress, executionProgressService, executionCancellationService,
			executionPayloadCodec, inventoryRunningState);

	@BeforeEach
	void enqueueAnswersWithWhatItWasGiven() {
		when(executionEnqueueService.enqueueOrExisting(any())).thenAnswer(call -> call.getArgument(0));
		when(workspaceManager.geodata()).thenReturn(workspace);

		launcher = new GeoLauncher(executionEnqueueService, executionPayloadCodec, backgroundWorkGate,
				workspaceManager);
	}

	/**
	 * The exclusion, as data. Two rows naming the same path is what makes the
	 * worker's advisory lock keep them apart - across processes, and without either
	 * type knowing the other exists.
	 */
	@Test
	void bothDeclareTheGeodataFolderAndThatIsTheWholeOfTheExclusion() {
		Execution rebuild = launcher.rebuildLocations(LocationRebuildScope.PENDING).orElseThrow();
		Execution update = launcher.updateDataset().orElseThrow();

		Assertions.assertThat(rebuild.getSourcePath()).isEqualTo(PathUtils.normalize(workspace));
		Assertions.assertThat(update.getSourcePath()).isEqualTo(rebuild.getSourcePath());
		Assertions.assertThat(rebuild.getExecutionType()).isEqualTo(ExecutionType.LOCATION_REBUILD);
		Assertions.assertThat(update.getExecutionType()).isEqualTo(ExecutionType.GEO_DATASET_UPDATE);
	}

	@Test
	void aScopeAskedForIsTheScopeThatTravels() {
		Execution queued = launcher.rebuildLocations(LocationRebuildScope.LOW_CONFIDENCE).orElseThrow();

		LocationRebuildPayload payload = executionPayloadCodec.decode(queued.getRequestPayload(),
				LocationRebuildPayload.class);

		Assertions.assertThat(payload.scope()).isEqualTo(LocationRebuildScope.LOW_CONFIDENCE);
		Assertions.assertThat(payload.schemaVersion()).isEqualTo(GeoMessages.PAYLOAD_SCHEMA_VERSION);
	}

	/** Two scopes are two questions; two asks for one scope are one question. */
	@Test
	void eachScopeIsItsOwnRequestAndAskingTwiceForOneIsNot() {
		Execution pending = launcher.rebuildLocations(LocationRebuildScope.PENDING).orElseThrow();
		Execution again = launcher.rebuildLocations(LocationRebuildScope.PENDING).orElseThrow();
		Execution all = launcher.rebuildLocations(LocationRebuildScope.ALL).orElseThrow();

		Assertions.assertThat(pending.getDedupKey()).isEqualTo(again.getDedupKey()).isNotEqualTo(all.getDedupKey());
	}

	/**
	 * The timer, the button and a second click ask for the same state - "current" -
	 * so they are one request whoever asked.
	 */
	@Test
	void everyAskerOfAnUpdateAsksForTheSameThing() {
		Execution fromTimer = launcher.updateDataset().orElseThrow();
		Execution fromButton = launcher.updateDataset().orElseThrow();

		Assertions.assertThat(fromTimer.getDedupKey()).isEqualTo(fromButton.getDedupKey());
	}

	@Test
	void nothingIsAskedForWhileTheApplicationIsClosing() {
		when(backgroundWorkGate.standDown()).thenReturn(true);

		Assertions.assertThat(launcher.rebuildLocations(LocationRebuildScope.ALL)).isEmpty();
		Assertions.assertThat(launcher.updateDataset()).isEmpty();

		verify(executionEnqueueService, never()).enqueueOrExisting(any());
	}

	@Test
	void aRebuildClaimedWhileAnInventoryIsRunningStepsAside() {
		when(inventoryRunningState.isRunning()).thenReturn(true);

		rebuildHandler.handle(execution(ExecutionType.LOCATION_REBUILD), claimed(rebuildPayload(1)), null);

		verify(locationRebuildService, never()).rebuild(any(), any(), any());
		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.REJECTED), any(), any());
	}

	@Test
	void anUpdateClaimedWhileAnInventoryIsRunningStepsAside() {
		when(inventoryRunningState.isRunning()).thenReturn(true);

		datasetHandler.handle(execution(ExecutionType.GEO_DATASET_UPDATE), claimed(datasetPayload(1)), null);

		verify(offlineGeoDataset, never()).bringUpToDate();
		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.REJECTED), any(), any());
	}

	/**
	 * A medium whose coordinates fall outside every boundary the dataset knows is
	 * not an error - it is counted apart, and only the errors decide the outcome.
	 */
	@Test
	void whatCouldNotBeMatchedIsCountedApartFromWhatFailed() {
		when(locationRebuildService.countCandidates(LocationRebuildScope.ALL)).thenReturn(100L);
		when(locationRebuildService.rebuild(any(), any(), any()))
				.thenReturn(new LocationRebuildResult(LocationRebuildScope.ALL, 100, 80, 20, 0));

		rebuildHandler.handle(execution(ExecutionType.LOCATION_REBUILD), claimed(rebuildPayload(1)), null);

		verify(executionProgressService).updateTotal(any(), eq(100));
		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.FINISHED),
				eq(new ExecutionCounts(100, 80, 20, 0)), any());
	}

	@Test
	void aRebuildWithErrorsSaysSoWithoutBeingAFailure() {
		when(locationRebuildService.rebuild(any(), any(), any()))
				.thenReturn(new LocationRebuildResult(LocationRebuildScope.ALL, 10, 8, 0, 2));

		rebuildHandler.handle(execution(ExecutionType.LOCATION_REBUILD), claimed(rebuildPayload(1)), null);

		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.FINISHED_WITH_ERRORS), any(), any());
	}

	@Test
	void aCancelledRebuildEndsCancelledAndKeepsWhatItWrote() {
		when(locationRebuildService.rebuild(any(), any(), any()))
				.thenReturn(new LocationRebuildResult(LocationRebuildScope.ALL, 10, 4, 0, 0));
		when(executionCancellationService.isCancelled(any())).thenReturn(true);

		rebuildHandler.handle(execution(ExecutionType.LOCATION_REBUILD), claimed(rebuildPayload(1)), null);

		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.CANCELLED), any(), any());
	}

	/**
	 * Progress and the stop signal both cross the process boundary through the row:
	 * the pass reports into it, and asks it whether to carry on.
	 */
	@Test
	void progressIsWrittenToTheRowAndTheStopSignalIsReadFromIt() {
		when(locationRebuildService.rebuild(any(), any(), any())).thenAnswer(call -> {
			LongConsumer progress = call.getArgument(1);
			BooleanSupplier stop = call.getArgument(2);

			progress.accept(7L);

			Assertions.assertThat(stop.getAsBoolean()).isFalse();

			when(inventoryRunningState.isRunning()).thenReturn(true);

			Assertions.assertThat(stop.getAsBoolean()).isTrue();

			return new LocationRebuildResult(LocationRebuildScope.ALL, 10, 7, 0, 0);
		});

		rebuildHandler.handle(execution(ExecutionType.LOCATION_REBUILD), claimed(rebuildPayload(1)), null);

		// Seven candidates resolved is seven, not nought: the counter used to be a
		// constant zero, so the bar stayed empty for the whole rebuild.
		verify(executionProgressService).updateLiveProgress(any(), eq(7), eq(7), eq(0), eq(0), any());
	}

	/**
	 * A finished update drops the resolution cache: the answers in it were read off
	 * boundaries that have just been replaced.
	 */
	@Test
	void aFinishedUpdateInvalidatesTheAnswersTheOldBoundariesGave() {
		when(offlineGeoDataset.bringUpToDate()).thenReturn(true);
		when(geoDatasetProgress.recordsImported()).thenReturn(1_234L);
		when(geoDatasetProgress.stagesDone()).thenReturn(9);

		datasetHandler.handle(execution(ExecutionType.GEO_DATASET_UPDATE), claimed(datasetPayload(1)), null);

		verify(offlineGeoDataset).bringUpToDate();
		verify(mediaLocationService).clearCache();
		verify(geoDatasetProgress).attach(any());
		verify(geoDatasetProgress).detach();
		// Nine of nine, not one thousand two hundred and thirty-four of nine. The
		// counter and its total are the first bar; feeding it the boundary count made
		// a finished update read as a ratio nobody could mean, and it only looked
		// right because the percentage is clamped at a hundred. What was imported is
		// what the sentence carries.
		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.FINISHED),
				eq(new ExecutionCounts(9, 1234, 0, 0)), any());
	}

	/**
	 * The ninth stage happens after the last functional work rather than beside it:
	 * the cache the new dataset invalidates is cleared first, and only then is the
	 * stage counted and the row closed. The other order would have the bar reach a
	 * hundred per cent with work still to do.
	 */
	@Test
	void theLastStageIsCountedOnlyAfterTheCacheTheNewDatasetInvalidates() {
		when(offlineGeoDataset.bringUpToDate()).thenReturn(true);
		when(geoDatasetProgress.stagesDone()).thenReturn(9);

		datasetHandler.handle(execution(ExecutionType.GEO_DATASET_UPDATE), claimed(datasetPayload(1)), null);

		InOrder order = inOrder(offlineGeoDataset, geoDatasetProgress, mediaLocationService,
				executionProgressService);

		order.verify(offlineGeoDataset).bringUpToDate();
		order.verify(geoDatasetProgress).finishing();
		order.verify(mediaLocationService).clearCache();
		order.verify(geoDatasetProgress).stageFinished();
		order.verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.FINISHED), any(), any());
	}

	/**
	 * The one point at which a dataset update can be stopped: before it starts.
	 *
	 * <p>
	 * Past it the acquisition is under way - files staged, rows imported in a
	 * transaction of its own, and only a successful import promoting what was
	 * staged - and stopping between the import and that promotion would leave the
	 * tables describing one version and the disk another. So this is asked once,
	 * here, where the answer costs nothing and nothing has happened yet.
	 */
	@Test
	void aDatasetUpdateAlreadyCancelledDoesNotStartDownloadingAnything() {
		when(executionCancellationService.isCancelled(any())).thenReturn(true);

		datasetHandler.handle(execution(ExecutionType.GEO_DATASET_UPDATE), claimed(datasetPayload(1)), null);

		verify(offlineGeoDataset, never()).bringUpToDate();
		verify(geoDatasetProgress, never()).attach(any());
		verify(mediaLocationService, never()).clearCache();
		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.CANCELLED), any(), any());
	}

	/**
	 * A failed acquisition leaves the previous dataset in place - the manager
	 * discards what it staged - and the exception reaches the dispatcher, which is
	 * what turns the row into a failure with its reason.
	 */
	@Test
	void aFailedUpdateLetsTheReasonOutAndStillDetaches() {
		when(offlineGeoDataset.bringUpToDate()).thenThrow(new IllegalStateException("the server refused"));

		Execution execution = execution(ExecutionType.GEO_DATASET_UPDATE);
		ClaimedExecution claimed = claimed(datasetPayload(1));

		Assertions.assertThatThrownBy(() -> datasetHandler.handle(execution, claimed, null))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("refused");

		verify(geoDatasetProgress).detach();
		verify(mediaLocationService, never()).clearCache();
	}

	@Test
	void aPayloadFromAnotherSchemaIsRefusedBeforeAnythingIsTouched() {
		Execution rebuild = execution(ExecutionType.LOCATION_REBUILD);
		ClaimedExecution rebuildClaim = claimed(rebuildPayload(99));

		Assertions.assertThatThrownBy(() -> rebuildHandler.handle(rebuild, rebuildClaim, null))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schema");

		Execution update = execution(ExecutionType.GEO_DATASET_UPDATE);
		ClaimedExecution updateClaim = claimed(datasetPayload(99));

		Assertions.assertThatThrownBy(() -> datasetHandler.handle(update, updateClaim, null))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schema");

		verify(locationRebuildService, never()).rebuild(any(), any(), any());
		verify(offlineGeoDataset, never()).bringUpToDate();
	}

	@Test
	void bothAreSafeToRunAgainFromTheStartAndOneAtATime() {
		Assertions.assertThat(rebuildHandler.resumable()).isTrue();
		Assertions.assertThat(datasetHandler.resumable()).isTrue();
		Assertions.assertThat(rebuildHandler.concurrencyLimit()).isEqualTo(1);
		Assertions.assertThat(datasetHandler.concurrencyLimit()).isEqualTo(1);
		Assertions.assertThat(rebuildHandler.type()).isEqualTo(ExecutionType.LOCATION_REBUILD);
		Assertions.assertThat(datasetHandler.type()).isEqualTo(ExecutionType.GEO_DATASET_UPDATE);
	}

	private Execution execution(ExecutionType type) {
		return Execution.builder().id(1L).executionType(type).build();
	}

	private ClaimedExecution claimed(String payload) {
		return new ClaimedExecution(1L, ExecutionType.LOCATION_REBUILD.name(), PathUtils.normalize(workspace), null,
				payload);
	}

	private String rebuildPayload(int schemaVersion) {
		return executionPayloadCodec.encode(new LocationRebuildPayload(schemaVersion, LocationRebuildScope.ALL));
	}

	private String datasetPayload(int schemaVersion) {
		return executionPayloadCodec.encode(new GeoDatasetPayload(schemaVersion));
	}

	/**
	 * The run that found nothing to do finishes like any other, and touches nothing
	 * on its way out.
	 *
	 * <p>
	 * The resolution cache is the point. Every answer in it was computed against
	 * the boundaries that are still installed, so clearing it here would throw away
	 * a working cache to mark an update that changed nothing - and every rebuild
	 * afterwards would pay to recompute what it already knew.
	 */
	@Test
	void anUpdateThatFoundNothingNewFinishesWithoutClearingAnything() {
		when(offlineGeoDataset.bringUpToDate()).thenReturn(false);
		when(geoDatasetProgress.stagesDone()).thenReturn(4);

		datasetHandler.handle(execution(ExecutionType.GEO_DATASET_UPDATE), claimed(datasetPayload(1)), null);

		verify(mediaLocationService, never()).clearCache();
		verify(geoDatasetProgress, never()).finishing();

		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.FINISHED), any(), any());
		verify(geoDatasetProgress).detach();
	}
}