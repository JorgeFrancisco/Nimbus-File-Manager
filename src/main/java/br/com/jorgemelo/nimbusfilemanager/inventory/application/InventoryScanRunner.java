package br.com.jorgemelo.nimbusfilemanager.inventory.application;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintBacklogLauncher;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancelledException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.constants.InventoryConstants;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryScanRequest;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.scanner.FileScanner;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import lombok.extern.slf4j.Slf4j;

/**
 * Walks the library and catalogues what it finds, in batches.
 *
 * <p>
 * This was a Spring Batch job: a JobRepository, a JobInstance, a JobExecution
 * and a StepExecution describing a run that {@link Execution} already
 * described. The one capability that would have paid for that second record is
 * restarting from a checkpoint, and it was never in use - the reader opened an
 * ExecutionContext but persisted no cursor, so every boot marked an unfinished
 * run INTERRUPTED rather than resuming it. A full pass is idempotent, so
 * running it again <em>is</em> the recovery.
 *
 * <p>
 * What the framework used to arrange is now three plain steps in one method,
 * and the order is not arbitrary: counting happens before the lock is taken so
 * the progress bar has a denominator, the lock covers the walk and every write,
 * and the outcome is decided once - finished, cancelled, refused or failed.
 */
@Slf4j
@Service
public class InventoryScanRunner {

	private final FileScanner fileScanner;
	private final OperationLockService operationLockService;
	private final ExecutionProgressService executionProgressService;
	private final ExecutionCancellationService executionCancellationService;
	private final InventoryBatchWriter inventoryBatchWriter;
	private final InventoryTelemetryRecorder telemetryRecorder;
	private final FingerprintBacklogLauncher fingerprintBacklogLauncher;

	public InventoryScanRunner(FileScanner fileScanner, OperationLockService operationLockService,
			ExecutionProgressService executionProgressService,
			ExecutionCancellationService executionCancellationService, InventoryBatchWriter inventoryBatchWriter,
			InventoryTelemetryRecorder telemetryRecorder, FingerprintBacklogLauncher fingerprintBacklogLauncher) {
		this.fileScanner = fileScanner;
		this.operationLockService = operationLockService;
		this.executionProgressService = executionProgressService;
		this.executionCancellationService = executionCancellationService;
		this.inventoryBatchWriter = inventoryBatchWriter;
		this.telemetryRecorder = telemetryRecorder;
		this.fingerprintBacklogLauncher = fingerprintBacklogLauncher;
	}

	public void run(Execution execution, InventoryScanRequest request) {
		Long executionId = execution.getId();

		telemetryRecorder.reset();

		try {
			scan(execution, request);
		} catch (ExecutionCancelledException _) {
			executionProgressService.cancel(execution, ExecutionMessages.inventoryCancelled());
		} catch (OperationLockException exception) {
			// Another operation holds the tree - typically an organization moving files
			// while the folder watch asked for an inventory. Refusing is the right answer
			// and it is not a failure, so it is reported as a rejection.
			log.info("Inventory skipped - {}", exception.getMessage());

			executionProgressService.fail(execution, ExecutionMessages.inventoryRejected(exception.getMessage()));
		} catch (RuntimeException exception) {
			log.error("Inventory failed", exception);

			executionProgressService.fail(execution, ExecutionMessages.inventoryFailed(exception.getMessage()));
		} finally {
			executionCancellationService.forget(executionId);

			telemetryRecorder.persist(executionId, telemetryRecorder.snapshot());

			// The pass just added media with no fingerprint, and both backlogs stood aside
			// while it ran. Asking for both matters: resuming only the photo one left
			// video hashing stopped until the next restart. Asking is all there is to do -
			// a backlog is whatever is still missing a fingerprint, so there is no
			// position to hand back, and the process that answers need not be this one.
			fingerprintBacklogLauncher.launchBoth();
		}
	}

	private void scan(Execution execution, InventoryScanRequest request) {
		countFiles(execution, request);

		InventoryCounters counters = new InventoryCounters();

		try (var _ = operationLockService.acquire(ExecutionType.INVENTORY, request.sourcePath());
				Stream<Path> files = fileScanner.stream(request.sourcePath(), request.scanOptions())) {
			writeInBatches(execution, request, files, counters);
		}

		finish(execution, counters);
	}

	private void countFiles(Execution execution, InventoryScanRequest request) {
		executionProgressService.updatePhase(execution, ExecutionPhase.SCANNING, ExecutionStepType.SCANNING_STARTED,
				ExecutionMessages.countingFiles());

		long scanStart = System.nanoTime();

		long total = fileScanner.count(request.sourcePath(), request.scanOptions());

		telemetryRecorder.recordScanCount(System.nanoTime() - scanStart, total);

		executionProgressService.updateTotal(execution, (int) Math.min(total, Integer.MAX_VALUE));

		executionProgressService.updatePhase(execution, ExecutionPhase.PROCESSING, ExecutionStepType.PROCESSING_STARTED,
				ExecutionMessages.processingFiles());
	}

	/**
	 * Feeds the walk to the writer a batch at a time. The stream is consumed
	 * lazily, so a library of any size is never held in memory - the same property
	 * the chunk-oriented step had, for the same reason.
	 */
	private void writeInBatches(Execution execution, InventoryScanRequest request, Stream<Path> files,
			InventoryCounters counters) {
		List<Path> batch = new ArrayList<>(InventoryConstants.BATCH_SIZE);

		for (Path file : (Iterable<Path>) files::iterator) {
			raiseWhenCancelled(execution.getId());

			batch.add(file);

			if (batch.size() == InventoryConstants.BATCH_SIZE) {
				inventoryBatchWriter.write(execution, request, List.copyOf(batch), counters);

				batch.clear();
			}
		}

		if (!batch.isEmpty()) {
			inventoryBatchWriter.write(execution, request, List.copyOf(batch), counters);
		}
	}

	private void raiseWhenCancelled(Long executionId) {
		if (executionCancellationService.isCancelled(executionId)) {
			throw new ExecutionCancelledException("Inventory cancelled by user.");
		}
	}

	private void finish(Execution execution, InventoryCounters counters) {
		ExecutionStatus status = counters.errors() > 0 ? ExecutionStatus.FINISHED_WITH_ERRORS
				: ExecutionStatus.FINISHED;

		executionProgressService.finish(execution, status, counters.found(), counters.analyzed(),
				counters.cacheHits(), counters.errors(), ExecutionMessages.inventoryCompleted());
	}
}