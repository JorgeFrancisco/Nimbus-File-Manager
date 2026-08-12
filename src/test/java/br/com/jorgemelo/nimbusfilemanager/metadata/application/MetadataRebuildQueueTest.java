package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.MetadataMessages;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataDateDifference;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildPayload;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildResponse;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildSimulationResult;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MetadataRebuildField;
import br.com.jorgemelo.nimbusfilemanager.shared.application.BackgroundWorkGate;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * The metadata rebuild as an intention and as a run.
 *
 * <p>
 * What the pair has to get right is that asking is cheap and joinable while
 * doing is exclusive, that a simulation is never mistaken for a pass that
 * writes, and that a payload from another version is refused before anything is
 * written - reading the dry-run flag wrong would rebuild the catalog when the
 * user asked to be shown what would happen.
 */
class MetadataRebuildQueueTest {

	/**
	 * A real absolute folder, because the launcher normalizes what it is given: a
	 * Windows-shaped literal is a single relative segment on Linux, and normalizing
	 * one prefixes the working directory - so the payload stopped carrying what the
	 * caller had asked for.
	 */
	@TempDir
	static Path requestedFolder;

	private static String FOLDER;

	@BeforeAll
	static void nameTheFolder() {
		FOLDER = PathUtils.normalize(requestedFolder);
	}

	private static final List<MetadataRebuildField> FIELDS = List.of(MetadataRebuildField.DATE);

	private static final Instant CUTOFF = Instant.parse("2026-07-26T11:16:00Z");
	/** The capture date a simulation would write: a reading, and so still local. */
	private static final LocalDateTime PROPOSED_CAPTURE_DATE = LocalDateTime.of(2026, Month.JULY, 26, 11, 16);

	private final ExecutionEnqueueService executionEnqueueService = mock(ExecutionEnqueueService.class);
	private final ExecutionPayloadCodec executionPayloadCodec = new ExecutionPayloadCodec(
			new ObjectMapper().findAndRegisterModules());
	private final BackgroundWorkGate backgroundWorkGate = mock(BackgroundWorkGate.class);

	private final MetadataRebuildLauncher launcher = new MetadataRebuildLauncher(executionEnqueueService,
			executionPayloadCodec, backgroundWorkGate);

	private final MetadataRebuildService metadataRebuildService = mock(MetadataRebuildService.class);
	private final MetadataRebuildPreviewWriter metadataRebuildPreviewWriter = mock(MetadataRebuildPreviewWriter.class);
	private final ExecutionProgressService executionProgressService = mock(ExecutionProgressService.class);
	private final ExecutionCancellationService executionCancellationService = mock(ExecutionCancellationService.class);
	private final InventoryRunningState inventoryRunningState = mock(InventoryRunningState.class);

	private final MetadataRebuildJobHandler handler = new MetadataRebuildJobHandler(metadataRebuildService,
			metadataRebuildPreviewWriter, executionProgressService, executionCancellationService,
			executionPayloadCodec, inventoryRunningState);

	@BeforeEach
	void enqueueAnswersWithWhatItWasGiven() {
		when(executionEnqueueService.enqueueOrExisting(any())).thenAnswer(call -> call.getArgument(0));
	}

	@Test
	void aRequestCarriesTheFolderTheFieldsAndTheCutoffItWasAskedWith() {
		Execution queued = launcher.launch(FOLDER, FIELDS, false, CUTOFF).orElseThrow();

		MetadataRebuildPayload payload = executionPayloadCodec.decode(queued.getRequestPayload(),
				MetadataRebuildPayload.class);

		Assertions.assertThat(queued.getExecutionType()).isEqualTo(ExecutionType.METADATA_REBUILD);
		Assertions.assertThat(payload.sourcePath()).isEqualTo(FOLDER);
		Assertions.assertThat(payload.refresh()).isEqualTo(FIELDS);
		Assertions.assertThat(payload.notAnalysedSince()).isEqualTo(CUTOFF);
		Assertions.assertThat(payload.dryRunValue()).isFalse();
	}

	/**
	 * Asking twice for the same folder is one request; the fields are out of the
	 * key on purpose, because the second ask would only re-read files the first is
	 * about to read anyway.
	 */
	@Test
	void twoRequestsOverTheSameFolderAreOneRequest() {
		Execution first = launcher.launch(FOLDER, FIELDS, false, null).orElseThrow();
		Execution second = launcher.launch(FOLDER, List.of(MetadataRebuildField.CAMERA), false, CUTOFF).orElseThrow();

		Assertions.assertThat(first.getDedupKey()).isEqualTo(second.getDedupKey());
	}

	@Test
	void aSimulationIsADifferentQuestionFromAPassThatWrites() {
		Execution rebuild = launcher.launch(FOLDER, FIELDS, false, null).orElseThrow();
		Execution simulation = launcher.launch(FOLDER, FIELDS, true, null).orElseThrow();

		Assertions.assertThat(rebuild.getDedupKey()).isNotEqualTo(simulation.getDedupKey());
		Assertions.assertThat(rebuild.getExecuteFlag()).isTrue();
		Assertions.assertThat(simulation.getExecuteFlag()).isFalse();
	}

	@Test
	void twoFoldersAreTwoRequests() {
		Execution photos = launcher.launch(FOLDER, FIELDS, false, null).orElseThrow();
		Execution videos = launcher.launch("D:\\videos", FIELDS, false, null).orElseThrow();

		Assertions.assertThat(photos.getDedupKey()).isNotEqualTo(videos.getDedupKey());
	}

	@Test
	void nothingIsAskedForWhileTheApplicationIsClosing() {
		when(backgroundWorkGate.standDown()).thenReturn(true);

		Assertions.assertThat(launcher.launch(FOLDER, FIELDS, false, null)).isEmpty();

		verify(executionEnqueueService, never()).enqueueOrExisting(any());
	}

	/**
	 * An inventory is cataloguing the very files this would re-read, and the two
	 * write the same rows. The row ends rejected rather than leaving a thread to
	 * try again.
	 */
	@Test
	void aRebuildClaimedWhileAnInventoryIsRunningStepsAside() {
		when(inventoryRunningState.isRunning()).thenReturn(true);

		handler.handle(execution(), claimed(payload(1, false)), null);

		verify(metadataRebuildService, never()).rebuild(any(), any(), any());
		verify(metadataRebuildService, never()).simulate(any());
		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.REJECTED), any(), any());
	}

	@Test
	void aPassThatWritesReportsItsCountsOnTheRow() {
		when(metadataRebuildService.countCandidates(any())).thenReturn(120L);
		when(metadataRebuildService.rebuild(any(), any(), any()))
				.thenReturn(new MetadataRebuildResponse(FOLDER, false, 120, 100, 12, 3, 2, 5, null));

		handler.handle(execution(), claimed(payload(1, false)), null);

		verify(executionProgressService).updateTotal(any(), eq(120));
		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.FINISHED_WITH_ERRORS),
				eq(new ExecutionCounts(120, 100, 12, 5)),
				any());
		verify(metadataRebuildPreviewWriter, never()).write(any(), any(), any());
	}

	@Test
	void aCleanPassFinishes() {
		when(metadataRebuildService.rebuild(any(), any(), any()))
				.thenReturn(new MetadataRebuildResponse(FOLDER, false, 10, 10, 0, 0, 0, 0, null));

		handler.handle(execution(), claimed(payload(1, false)), null);

		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.FINISHED), any(), any());
	}

	@Test
	void aCancelledPassEndsCancelledAndKeepsWhatItWrote() {
		when(metadataRebuildService.rebuild(any(), any(), any()))
				.thenReturn(new MetadataRebuildResponse(FOLDER, false, 10, 4, 0, 0, 0, 0, null));
		when(executionCancellationService.isCancelled(any())).thenReturn(true);

		handler.handle(execution(), claimed(payload(1, false)), null);

		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.CANCELLED), any(), any());
	}

	/**
	 * Progress and the stop signal both cross the process boundary through the
	 * row: the pass reports into it as it goes, and asks it whether to carry on.
	 */
	@Test
	void progressIsWrittenToTheRowAndTheStopSignalIsReadFromIt() {
		when(metadataRebuildService.rebuild(any(), any(), any())).thenAnswer(call -> {
			LongConsumer progress = call.getArgument(1);
			BooleanSupplier stop = call.getArgument(2);

			progress.accept(42L);

			Assertions.assertThat(stop.getAsBoolean()).isFalse();

			when(inventoryRunningState.isRunning()).thenReturn(true);

			Assertions.assertThat(stop.getAsBoolean()).isTrue();

			return new MetadataRebuildResponse(FOLDER, false, 100, 42, 0, 0, 0, 0, null);
		});

		handler.handle(execution(), claimed(payload(1, false)), null);

		verify(executionProgressService).updateLiveProgress(any(), eq(0), eq(42), eq(0), eq(0), any());
	}

	/**
	 * A simulation writes no metadata and leaves what it found where the screen
	 * reads it. The counters say candidates and nothing rebuilt, because nothing
	 * was.
	 */
	@Test
	void aSimulationPublishesItsPreviewAndWritesNoMetadata() {
		when(metadataRebuildService.simulate(any())).thenReturn(new MetadataRebuildSimulationResult(80, 20, 50, 7,
				List.of(new MetadataDateDifference("D:\\photos\\a.jpg", null, null, PROPOSED_CAPTURE_DATE, DateSource.EXIF))));

		handler.handle(execution(), claimed(payload(1, true)), null);

		ArgumentCaptor<MetadataRebuildSimulationResult> published = ArgumentCaptor
				.forClass(MetadataRebuildSimulationResult.class);

		verify(metadataRebuildPreviewWriter).write(eq(1L), eq(FOLDER), published.capture());
		verify(metadataRebuildService, never()).rebuild(any(), any(), any());
		verify(executionProgressService).finishCommand(any(), eq(ExecutionStatus.FINISHED), any(), any());

		Assertions.assertThat(published.getValue().wouldChange()).isEqualTo(7);
	}

	@Test
	void aPayloadFromAnotherSchemaIsRefusedBeforeAnythingIsWritten() {
		Execution execution = execution();
		ClaimedExecution claimed = claimed(payload(99, false));

		Assertions.assertThatThrownBy(() -> handler.handle(execution, claimed, null))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schema");

		verify(metadataRebuildService, never()).rebuild(any(), any(), any());
		verify(metadataRebuildService, never()).simulate(any());
	}

	@Test
	void aRebuildIsSafeToRunAgainFromTheStart() {
		Assertions.assertThat(handler.resumable()).isTrue();
		Assertions.assertThat(handler.type()).isEqualTo(ExecutionType.METADATA_REBUILD);
		Assertions.assertThat(handler.concurrencyLimit()).isEqualTo(1);
	}

	private Execution execution() {
		return Execution.builder().id(1L).executionType(ExecutionType.METADATA_REBUILD).build();
	}

	private ClaimedExecution claimed(String payload) {
		return new ClaimedExecution(1L, ExecutionType.METADATA_REBUILD.name(), FOLDER, null, payload);
	}

	private String payload(int schemaVersion, boolean dryRun) {
		return executionPayloadCodec
				.encode(new MetadataRebuildPayload(schemaVersion, FOLDER, FIELDS, dryRun, null));
	}

	@Test
	void theSchemaTheLauncherWritesIsTheOneTheHandlerReads() {
		Execution queued = launcher.launch(FOLDER, FIELDS, false, null).orElseThrow();

		MetadataRebuildPayload payload = executionPayloadCodec.decode(queued.getRequestPayload(),
				MetadataRebuildPayload.class);

		Assertions.assertThat(payload.schemaVersion()).isEqualTo(MetadataMessages.PAYLOAD_SCHEMA_VERSION);
	}
}