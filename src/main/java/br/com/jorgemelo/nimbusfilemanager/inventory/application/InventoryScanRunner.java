package br.com.jorgemelo.nimbusfilemanager.inventory.application;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.EligibilityAnnouncer;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintBacklogLauncher;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancelledException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.constants.InventoryConstants;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.InventoryScanRequest;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.PersistedCursor;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.ScannedFile;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.scanner.FileScanner;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.watch.source.usn.JournalCheckpoint;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionMetricsContext;
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
	private final EligibilityAnnouncer eligibilityAnnouncer;
	private final JournalCheckpoint journalCheckpoint;

	public InventoryScanRunner(FileScanner fileScanner, OperationLockService operationLockService,
			ExecutionProgressService executionProgressService,
			ExecutionCancellationService executionCancellationService, InventoryBatchWriter inventoryBatchWriter,
			InventoryTelemetryRecorder telemetryRecorder, FingerprintBacklogLauncher fingerprintBacklogLauncher,
			EligibilityAnnouncer eligibilityAnnouncer, JournalCheckpoint journalCheckpoint) {
		this.fileScanner = fileScanner;
		this.operationLockService = operationLockService;
		this.executionProgressService = executionProgressService;
		this.executionCancellationService = executionCancellationService;
		this.inventoryBatchWriter = inventoryBatchWriter;
		this.telemetryRecorder = telemetryRecorder;
		this.fingerprintBacklogLauncher = fingerprintBacklogLauncher;
		this.eligibilityAnnouncer = eligibilityAnnouncer;
		this.journalCheckpoint = journalCheckpoint;
	}

	public void run(Execution execution, InventoryScanRequest request, ExecutionOwnership ownership) {
		Long executionId = execution.getId();

		// Born here and nowhere else: this run's own accumulators, which no other
		// execution can reach and which nothing has to clear beforehand.
		ExecutionMetricsContext metricsContext = new ExecutionMetricsContext();

		// Owned here rather than by the walk so that a pass which was cancelled or
		// failed halfway still reports what it did before it stopped: the files it
		// wrote are written, and a cancellation does not put them back.
		InventoryCounters counters = new InventoryCounters();

		// Read before a single file is looked at, and stored at the bottom only if the
		// walk got all the way through. A change numbered below this happened before
		// the walk and the walk saw it; a change numbered above it is what the next
		// start replays. See JournalCheckpoint for why the two orders are not
		// interchangeable.
		PersistedCursor watermark = journalCheckpoint.capture(request.sourcePath()).orElse(null);

		boolean walked = false;

		try {
			scan(execution, request, counters, ownership, metricsContext);

			walked = true;
		} catch (ExecutionCancelledException _) {
			executionProgressService.cancel(ownership, ExecutionMessages.inventoryCancelled());
		} catch (OperationLockException exception) {
			// Another operation holds the tree - typically an organization moving files
			// while the folder watch asked for an inventory. Refusing is the right answer
			// and it is not a failure, so it is reported as a rejection.
			log.info("Inventory skipped - {}", exception.getMessage());

			executionProgressService.fail(ownership, ExecutionMessages.inventoryRejected(exception.getMessage()));
		} catch (RuntimeException exception) {
			log.error("Inventory failed", exception);

			executionProgressService.fail(ownership, ExecutionMessages.inventoryFailed(exception.getMessage()));
		} finally {
			executionCancellationService.forget(executionId);

			// After the outcome, never before: the aggregate records the run's duration,
			// and the duration only exists once finished_at is committed.
			telemetryRecorder.consolidate(ownership, metricsContext);

			// The pass just added media with no fingerprint, and both backlogs stood aside
			// while it ran. Asking for both matters: resuming only the photo one left
			// video hashing stopped until the next restart. Asking is all there is to do -
			// a backlog is whatever is still missing a fingerprint, so there is no
			// position to hand back, and the process that answers need not be this one.
			fingerprintBacklogLauncher.launchBoth();

			// Only a walk that finished has earned it. A cancelled or failed pass leaves
			// the older cursor in place, which costs a wider replay and never a missed
			// change.
			if (walked) {
				journalCheckpoint.advance(request.sourcePath(), watermark);
			}

			// Files the catalog had given up on and found again. They come back to the set
			// a duplicate analysis may look at, carrying the fingerprint they were hashed
			// with, so what is needed is a regroup and not a comparison - said once for the
			// pass, and not at all for the ordinary pass that found nothing lost.
			if (counters.reactivated() > 0) {
				eligibilityAnnouncer.announce("inventory");
			}
		}
	}

	private void scan(Execution execution, InventoryScanRequest request, InventoryCounters counters,
			ExecutionOwnership ownership, ExecutionMetricsContext metricsContext) {
		countFiles(request, ownership, metricsContext);

		try (var _ = operationLockService.acquire(ExecutionType.INVENTORY, request.sourcePath());
				Stream<ScannedFile> files = fileScanner.stream(request.sourcePath(), request.scanOptions())) {
			writeInBatches(execution, request, files, counters, ownership, metricsContext);
		}

		finish(counters, ownership);
	}

	private void countFiles(InventoryScanRequest request, ExecutionOwnership ownership,
			ExecutionMetricsContext metricsContext) {
		executionProgressService.updatePhase(ownership, ExecutionPhase.SCANNING, ExecutionStepType.SCANNING_STARTED,
				ExecutionMessages.countingFiles());

		long scanStart = System.nanoTime();

		long total = fileScanner.count(request.sourcePath(), request.scanOptions());

		telemetryRecorder.recordScanCount(metricsContext, System.nanoTime() - scanStart, total);

		executionProgressService.updateTotal(ownership, (int) Math.min(total, Integer.MAX_VALUE));

		executionProgressService.updatePhase(ownership, ExecutionPhase.PROCESSING, ExecutionStepType.PROCESSING_STARTED,
				ExecutionMessages.processingFiles());
	}

	/**
	 * Feeds the walk to the writer a batch at a time. The stream is consumed
	 * lazily, so a library of any size is never held in memory - the same property
	 * the chunk-oriented step had, for the same reason.
	 */
	private void writeInBatches(Execution execution, InventoryScanRequest request, Stream<ScannedFile> files,
			InventoryCounters counters, ExecutionOwnership ownership, ExecutionMetricsContext metricsContext) {
		List<ScannedFile> batch = new ArrayList<>(InventoryConstants.BATCH_SIZE);

		for (ScannedFile file : (Iterable<ScannedFile>) files::iterator) {
			raiseWhenCancelled(execution.getId());

			batch.add(file);

			if (batch.size() == InventoryConstants.BATCH_SIZE) {
				inventoryBatchWriter.write(execution, request, List.copyOf(batch), counters, ownership, metricsContext);

				batch.clear();
			}
		}

		if (!batch.isEmpty()) {
			inventoryBatchWriter.write(execution, request, List.copyOf(batch), counters, ownership, metricsContext);
		}
	}

	private void raiseWhenCancelled(Long executionId) {
		if (executionCancellationService.isCancelled(executionId)) {
			throw new ExecutionCancelledException("Inventory cancelled by user.");
		}
	}

	private void finish(InventoryCounters counters, ExecutionOwnership ownership) {
		ExecutionStatus status = counters.errors() > 0 ? ExecutionStatus.FINISHED_WITH_ERRORS
				: ExecutionStatus.FINISHED;

		executionProgressService.finish(ownership, status, counters.found(), counters.analyzed(), counters.cacheHits(),
				counters.errors(), ExecutionMessages.inventoryCompleted());
	}
}