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
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintBacklogLauncher;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLock;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.constants.InventoryConstants;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryScanRequest;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.ScanOptions;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.scanner.FileScanner;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataOptions;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

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
	private final ExecutionCancellationService executionCancellationService = mock(
			ExecutionCancellationService.class);
	private final InventoryBatchWriter inventoryBatchWriter = mock(InventoryBatchWriter.class);
	private final InventoryTelemetryRecorder telemetryRecorder = mock(InventoryTelemetryRecorder.class);
	private final FingerprintBacklogLauncher fingerprintBacklogLauncher = mock(FingerprintBacklogLauncher.class);

	private final InventoryScanRunner runner = new InventoryScanRunner(fileScanner, operationLockService,
			executionProgressService, executionCancellationService, inventoryBatchWriter, telemetryRecorder,
			fingerprintBacklogLauncher);

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

	@Test
	void countsBeforeWalkingSoTheProgressBarHasADenominator() {
		when(fileScanner.count(any(), any())).thenReturn(7L);
		when(fileScanner.stream(any(), any())).thenReturn(Stream.of());

		runner.run(execution, request);

		verify(executionProgressService).updateTotal(execution, 7);
		verify(executionProgressService).updatePhase(eq(execution), eq(ExecutionPhase.SCANNING),
				eq(ExecutionStepType.SCANNING_STARTED), any());
		verify(executionProgressService).updatePhase(eq(execution), eq(ExecutionPhase.PROCESSING),
				eq(ExecutionStepType.PROCESSING_STARTED), any());
	}

	@Test
	void holdsTheTreeWhileItWalksAndWrites() {
		when(fileScanner.stream(any(), any())).thenReturn(Stream.of(Path.of("a.jpg")));

		runner.run(execution, request);

		verify(operationLockService).acquire(ExecutionType.INVENTORY, request.sourcePath());
	}

	/**
	 * The chunk, which was the framework's job. A batch is written as soon as it
	 * fills, and the remainder is written at the end - never one giant
	 * transaction, and never a lost tail.
	 */
	@Test
	void writesAFullBatchAndThenTheRemainder() {
		int files = InventoryConstants.BATCH_SIZE + 3;

		when(fileScanner.stream(any(), any()))
				.thenReturn(IntStream.range(0, files).mapToObj(index -> Path.of(index + ".jpg")));

		runner.run(execution, request);

		ArgumentCaptor<List<Path>> batches = ArgumentCaptor.captor();

		verify(inventoryBatchWriter, times(2)).write(eq(execution), eq(request), batches.capture(), any());

		assertThat(batches.getAllValues().get(0)).hasSize(InventoryConstants.BATCH_SIZE);
		assertThat(batches.getAllValues().get(1)).hasSize(3);
	}

	@Test
	void writesNothingWhenTheLibraryIsEmpty() {
		when(fileScanner.stream(any(), any())).thenReturn(Stream.of());

		runner.run(execution, request);

		verify(inventoryBatchWriter, never()).write(any(), any(), any(), any());
		verify(executionProgressService).finish(eq(execution), eq(ExecutionStatus.FINISHED), anyInt(), anyInt(),
				anyInt(), anyInt(), any());
	}

	/**
	 * The outcome follows the tally the pass kept, not the row it started from -
	 * a file that failed to catalogue makes the run finish with errors.
	 */
	@Test
	void finishesWithErrorsWhenAFileFailedToCatalogue() {
		when(fileScanner.stream(any(), any())).thenReturn(Stream.of(Path.of("a.jpg")));

		doAnswer(invocation -> {
			invocation.getArgument(3, InventoryCounters.class).countError();

			return null;
		}).when(inventoryBatchWriter).write(any(), any(), any(), any());

		runner.run(execution, request);

		verify(executionProgressService).finish(eq(execution), eq(ExecutionStatus.FINISHED_WITH_ERRORS), anyInt(),
				anyInt(), anyInt(), eq(1), any());
	}

	@Test
	void stopsAndMarksCancelledWhenTheUserAsks() {
		when(fileScanner.stream(any(), any())).thenReturn(Stream.of(Path.of("a.jpg"), Path.of("b.jpg")));
		when(executionCancellationService.isCancelled(1L)).thenReturn(true);

		runner.run(execution, request);

		verify(executionProgressService).cancel(eq(execution), any(ExecutionMessage.class));
		verify(inventoryBatchWriter, never()).write(any(), any(), any(), any());
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

		runner.run(execution, request);

		verify(executionProgressService).fail(eq(execution), any(ExecutionMessage.class));
		verify(executionProgressService, never()).finish(any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), any());
	}

	@Test
	void reportsAFailureWhenTheWalkBreaks() {
		when(fileScanner.stream(any(), any())).thenThrow(new IllegalStateException("volume gone"));

		runner.run(execution, request);

		verify(executionProgressService).fail(eq(execution), any(ExecutionMessage.class));
	}

	/**
	 * The finally block, which matters more than it looks: an execution left
	 * registered would keep recovery from ever declaring it orphaned, and a
	 * backlog left standing aside would sit idle until the next restart.
	 */
	@Test
	void alwaysUnregistersAndResumesTheBacklogEvenWhenItFails() {
		when(fileScanner.stream(any(), any())).thenThrow(new IllegalStateException("volume gone"));

		runner.run(execution, request);

		verify(executionCancellationService).forget(1L);
		verify(fingerprintBacklogLauncher).launchBoth();
		verify(telemetryRecorder).persist(eq(1L), any());
	}

	@Test
	void recordsHowLongTheCountTook() {
		when(fileScanner.count(any(), any())).thenReturn(42L);
		when(fileScanner.stream(any(), any())).thenReturn(Stream.of());

		runner.run(execution, request);

		verify(telemetryRecorder).reset();
		verify(telemetryRecorder).recordScanCount(anyLong(), eq(42L));
	}
}