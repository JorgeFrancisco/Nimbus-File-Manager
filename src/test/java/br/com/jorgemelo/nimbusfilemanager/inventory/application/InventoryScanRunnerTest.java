package br.com.jorgemelo.nimbusfilemanager.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.EligibilityAnnouncer;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintBacklogLauncher;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLock;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.constants.InventoryConstants;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryScanRequest;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.PersistedCursor;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.ScanOptions;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.ScannedFile;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.scanner.FileScanner;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn.JournalCheckpoint;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataOptions;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionMetricsContext;

/**
 * The inventory pass, now that it is a method instead of a Spring Batch job.
 *
 * <p>
 * Everything the framework used to guarantee has to be asserted here instead:
 * that the walk is written in batches rather than all at once, that the tree is
 * locked while it happens, that a cancel is honoured between files, and that
 * every way the pass can end reaches exactly one final status.
 */
class InventoryScanRunnerTest {

	private final FileScanner fileScanner = mock(FileScanner.class);
	private final OperationLockService operationLockService = mock(OperationLockService.class);
	private final ExecutionProgressService executionProgressService = mock(ExecutionProgressService.class);
	private final ExecutionOwnership ownership = Takings.owning(1L);
	private final ExecutionCancellationService executionCancellationService = mock(ExecutionCancellationService.class);
	private final InventoryBatchWriter inventoryBatchWriter = mock(InventoryBatchWriter.class);
	private final InventoryTelemetryRecorder telemetryRecorder = mock(InventoryTelemetryRecorder.class);
	private final FingerprintBacklogLauncher fingerprintBacklogLauncher = mock(FingerprintBacklogLauncher.class);
	private final EligibilityAnnouncer eligibilityAnnouncer = mock(EligibilityAnnouncer.class);

	private final JournalCheckpoint journalCheckpoint = mock(JournalCheckpoint.class);

	private final InventoryScanRunner runner = new InventoryScanRunner(fileScanner, operationLockService,
			executionProgressService, executionCancellationService, inventoryBatchWriter, telemetryRecorder,
			fingerprintBacklogLauncher, eligibilityAnnouncer, journalCheckpoint);

	private Execution execution;

	private InventoryScanRequest request;

	@BeforeEach
	void setUp(@TempDir Path folder) {
		execution = Execution.builder().executionType(ExecutionType.INVENTORY).status(ExecutionStatus.RUNNING).build();
		execution.setId(1L);
		execution.setErrors(0);

		request = new InventoryScanRequest(folder, new ScanOptions(true, false, List.of(), List.of(), List.of()),
				new MetadataOptions(true, false));

		when(operationLockService.acquire(any(), any(Path[].class))).thenReturn(mock(OperationLock.class));
	}

	/**
	 * <b>The checkpoint is earned, not taken.</b> A walk that got all the way
	 * through means the catalog reflects everything that existed before it started,
	 * so the position read before it started is now a safe place for the next start
	 * to replay from.
	 */
	@Test
	void aWalkThatFinishedAdvancesTheJournalCheckpoint() {
		PersistedCursor watermark = new PersistedCursor(7L, 500L);

		when(journalCheckpoint.capture(any())).thenReturn(Optional.of(watermark));
		when(fileScanner.stream(any(), any())).thenReturn(Stream.of());

		runner.run(execution, request, ownership);

		verify(journalCheckpoint).advance(request.sourcePath(), watermark);
	}

	/**
	 * A cancelled pass proves nothing about the part of the tree it never reached,
	 * so the older cursor stands and the next start replays a wider window. That is
	 * the trade this whole mechanism is built on: repeat rather than miss.
	 */
	@Test
	void aCancelledWalkLeavesTheCheckpointWhereItWas() {
		when(journalCheckpoint.capture(any())).thenReturn(Optional.of(new PersistedCursor(7L, 500L)));
		when(executionCancellationService.isCancelled(any())).thenReturn(true);
		when(fileScanner.stream(any(), any())).thenReturn(Stream.of(scanned("a.jpg")));

		runner.run(execution, request, ownership);

		verify(journalCheckpoint, never()).advance(any(), any());
	}

	/** And neither does a pass that failed on the way. */
	@Test
	void aFailedWalkLeavesTheCheckpointWhereItWas() {
		when(journalCheckpoint.capture(any())).thenReturn(Optional.of(new PersistedCursor(7L, 500L)));
		when(fileScanner.stream(any(), any())).thenThrow(new IllegalStateException("the disk went away"));

		runner.run(execution, request, ownership);

		verify(journalCheckpoint, never()).advance(any(), any());
	}

	/**
	 * <b>The ordering that makes the checkpoint safe.</b> The journal moves while
	 * the tree is being walked - that is the normal case on a volume the user is
	 * still using - and what gets stored has to be where it was <em>before</em> the
	 * walk. Storing where it ended would claim the walk had seen changes that
	 * arrived behind it, and those changes would never be replayed again.
	 */
	@Test
	void storesTheWatermarkReadBeforeTheWalkRatherThanAfterIt() {
		PersistedCursor beforeTheWalk = new PersistedCursor(7L, 500L);

		when(journalCheckpoint.capture(any())).thenReturn(Optional.of(beforeTheWalk));

		when(fileScanner.stream(any(), any())).thenAnswer(_ -> {
			// A change lands while the walk is in progress; the journal is now at 900.
			when(journalCheckpoint.capture(any())).thenReturn(Optional.of(new PersistedCursor(7L, 900L)));

			return Stream.of();
		});

		runner.run(execution, request, ownership);

		verify(journalCheckpoint).advance(request.sourcePath(), beforeTheWalk);
		verify(journalCheckpoint, never()).advance(request.sourcePath(), new PersistedCursor(7L, 900L));
	}

	/** No journal, no checkpoint, and no complaint about it either. */
	@Test
	void aRootWithoutAJournalSimplyTakesNoCheckpoint() {
		when(journalCheckpoint.capture(any())).thenReturn(Optional.empty());
		when(fileScanner.stream(any(), any())).thenReturn(Stream.of());

		runner.run(execution, request, ownership);

		verify(journalCheckpoint).advance(request.sourcePath(), null);
	}

	@Test
	void countsBeforeWalkingSoTheProgressBarHasADenominator() {
		when(fileScanner.count(any(), any())).thenReturn(7L);
		when(fileScanner.stream(any(), any())).thenReturn(Stream.of());

		runner.run(execution, request, ownership);

		verify(executionProgressService).updateTotal(ownership, 7);
		verify(executionProgressService).updatePhase(eq(ownership), eq(ExecutionPhase.SCANNING),
				eq(ExecutionStepType.SCANNING_STARTED), any());
		verify(executionProgressService).updatePhase(eq(ownership), eq(ExecutionPhase.PROCESSING),
				eq(ExecutionStepType.PROCESSING_STARTED), any());
	}

	/**
	 * A pass that found files the catalog had given up on says so once, at the end,
	 * however many batches it took. Those files come back to what a duplicate
	 * analysis may look at, carrying the fingerprint they already had.
	 */
	@Test
	void aPassThatBroughtFilesBackAsksForOneRegroup() {
		when(fileScanner.stream(any(), any())).thenReturn(Stream.of(scanned("a.jpg"), scanned("b.jpg")));

		doAnswer(invocation -> {
			InventoryCounters counters = invocation.getArgument(3);

			counters.countReactivated();
			counters.countReactivated();

			return null;
		}).when(inventoryBatchWriter).write(any(), any(), any(), any(), any(), any());

		runner.run(execution, request, ownership);

		verify(eligibilityAnnouncer).announce("inventory");
	}

	/**
	 * The ordinary pass, which is nearly every one of them: files were catalogued
	 * or found unchanged, and nobody came back from the dead.
	 */
	@Test
	void anOrdinaryPassAsksForNothing() {
		when(fileScanner.stream(any(), any())).thenReturn(Stream.of(scanned("a.jpg")));

		runner.run(execution, request, ownership);

		verifyNoInteractions(eligibilityAnnouncer);
	}

	@Test
	void holdsTheTreeWhileItWalksAndWrites() {
		when(fileScanner.stream(any(), any())).thenReturn(Stream.of(scanned("a.jpg")));

		runner.run(execution, request, ownership);

		verify(operationLockService).acquire(ExecutionType.INVENTORY, request.sourcePath());
	}

	/**
	 * The chunk, which was the framework's job. A batch is written as soon as it
	 * fills, and the remainder is written at the end - never one giant transaction,
	 * and never a lost tail.
	 */
	@Test
	void writesAFullBatchAndThenTheRemainder() {
		int files = InventoryConstants.BATCH_SIZE + 3;

		when(fileScanner.stream(any(), any()))
				.thenReturn(IntStream.range(0, files).mapToObj(index -> scanned(index + ".jpg")));

		runner.run(execution, request, ownership);

		ArgumentCaptor<List<ScannedFile>> batches = ArgumentCaptor.captor();

		verify(inventoryBatchWriter, times(2)).write(eq(execution), eq(request), batches.capture(), any(), any(),
				any());

		assertThat(batches.getAllValues().get(0)).hasSize(InventoryConstants.BATCH_SIZE);
		assertThat(batches.getAllValues().get(1)).hasSize(3);
	}

	@Test
	void writesNothingWhenTheLibraryIsEmpty() {
		when(fileScanner.stream(any(), any())).thenReturn(Stream.of());

		runner.run(execution, request, ownership);

		verify(inventoryBatchWriter, never()).write(any(), any(), any(), any(), any(), any());
		verify(executionProgressService).finish(eq(ownership), eq(ExecutionStatus.FINISHED), anyInt(), anyInt(),
				anyInt(), anyInt(), any());
	}

	/**
	 * The outcome follows the tally the pass kept, not the row it started from - a
	 * file that failed to catalogue makes the run finish with errors.
	 */
	@Test
	void finishesWithErrorsWhenAFileFailedToCatalogue() {
		when(fileScanner.stream(any(), any())).thenReturn(Stream.of(scanned("a.jpg")));

		doAnswer(invocation -> {
			invocation.getArgument(3, InventoryCounters.class).countError();

			return null;
		}).when(inventoryBatchWriter).write(any(), any(), any(), any(), any(), any());

		runner.run(execution, request, ownership);

		verify(executionProgressService).finish(eq(ownership), eq(ExecutionStatus.FINISHED_WITH_ERRORS), anyInt(),
				anyInt(), anyInt(), eq(1), any());
	}

	@Test
	void stopsAndMarksCancelledWhenTheUserAsks() {
		when(fileScanner.stream(any(), any())).thenReturn(Stream.of(scanned("a.jpg"), scanned("b.jpg")));
		when(executionCancellationService.isCancelled(1L)).thenReturn(true);

		runner.run(execution, request, ownership);

		verify(executionProgressService).cancel(eq(ownership), any(ExecutionMessage.class));
		verify(inventoryBatchWriter, never()).write(any(), any(), any(), any(), any(), any());
		verify(executionProgressService, never()).finish(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), any());
	}

	/**
	 * A busy tree is a refusal, not a failure: the folder watch races an
	 * organization routinely, and the run that loses simply does not happen.
	 */
	@Test
	void reportsARejectionWhenAnotherOperationHoldsTheTree() {
		when(operationLockService.acquire(any(), any(Path[].class)))
				.thenThrow(new OperationLockException("D:\\fotos is busy"));

		runner.run(execution, request, ownership);

		verify(executionProgressService).fail(eq(ownership), any(ExecutionMessage.class));
		verify(executionProgressService, never()).finish(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), any());
	}

	@Test
	void reportsAFailureWhenTheWalkBreaks() {
		when(fileScanner.stream(any(), any())).thenThrow(new IllegalStateException("volume gone"));

		runner.run(execution, request, ownership);

		verify(executionProgressService).fail(eq(ownership), any(ExecutionMessage.class));
	}

	/**
	 * The finally block, which matters more than it looks: an execution left
	 * registered would keep recovery from ever declaring it orphaned, and a backlog
	 * left standing aside would sit idle until the next restart.
	 */
	@Test
	void alwaysUnregistersAndResumesTheBacklogEvenWhenItFails() {
		when(fileScanner.stream(any(), any())).thenThrow(new IllegalStateException("volume gone"));

		runner.run(execution, request, ownership);

		verify(executionCancellationService).forget(1L);
		verify(fingerprintBacklogLauncher).launchBoth();
		verify(telemetryRecorder).consolidate(eq(ownership), any());
	}

	@Test
	void recordsHowLongTheCountTook() {
		when(fileScanner.count(any(), any())).thenReturn(42L);
		when(fileScanner.stream(any(), any())).thenReturn(Stream.of());

		runner.run(execution, request, ownership);

		verify(telemetryRecorder).recordScanCount(any(), anyLong(), eq(42L));
	}

	/**
	 * What used to be a clearing of shared accumulators before the pass began.
	 * There is nothing to clear now because there is nothing shared: the run makes
	 * its own context, and the proof of that is identity - the count, every batch
	 * and the persistence all receive the very same object, and no other run can
	 * name it.
	 */
	@Test
	void carriesOneContextOfItsOwnFromTheCountThroughToThePersistence() {
		when(fileScanner.count(any(), any())).thenReturn(2L);
		when(fileScanner.stream(any(), any())).thenReturn(Stream.of(scanned("a.jpg"), scanned("b.jpg")));

		runner.run(execution, request, ownership);

		ArgumentCaptor<ExecutionMetricsContext> counted = ArgumentCaptor.captor();
		ArgumentCaptor<ExecutionMetricsContext> written = ArgumentCaptor.captor();
		ArgumentCaptor<ExecutionMetricsContext> persisted = ArgumentCaptor.captor();

		verify(telemetryRecorder).recordScanCount(counted.capture(), anyLong(), eq(2L));
		verify(inventoryBatchWriter).write(any(), any(), any(), any(), any(), written.capture());
		verify(telemetryRecorder).consolidate(eq(ownership), persisted.capture());

		assertThat(counted.getValue()).isNotNull();
		assertThat(written.getValue()).isSameAs(counted.getValue());
		assertThat(persisted.getValue()).isSameAs(counted.getValue());
	}

	/** Two passes never meet: each brings a context the other cannot reach. */
	@SuppressWarnings("unchecked")
	@Test
	void aSecondPassBringsADifferentContextInsteadOfReusingTheFirst() {
		when(fileScanner.stream(any(), any())).thenReturn(Stream.of(), Stream.of());

		runner.run(execution, request, ownership);
		runner.run(execution, request, ownership);

		ArgumentCaptor<ExecutionMetricsContext> contexts = ArgumentCaptor.captor();

		verify(telemetryRecorder, times(2)).consolidate(eq(ownership), contexts.capture());

		assertThat(contexts.getAllValues().get(1)).isNotSameAs(contexts.getAllValues().get(0));
	}

	/**
	 * A file as the walk hands it over: the path and the stat the walk already paid
	 * for. Dropping the attributes here would make the test describe a scanner that
	 * forces every consumer to stat the file again.
	 */
	private static ScannedFile scanned(String name) {
		return scanned(Path.of(name));
	}

	private static ScannedFile scanned(Path path) {
		return new ScannedFile(path, 1024L, Instant.EPOCH);
	}

}