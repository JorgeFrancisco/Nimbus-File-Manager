package br.com.jorgemelo.nimbusfilemanager.inventory.application.batch;

import java.util.List;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.PhashBacklogAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.dto.ScanOptions;
import br.com.jorgemelo.nimbusfilemanager.inventory.application.scanner.FileScanner;
import br.com.jorgemelo.nimbusfilemanager.processing.application.dto.Snapshot;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Drives the {@link Execution} row's status transitions around the inventory
 * job, mirroring what the old InventoryScanner did inline: pre-count the total
 * before reading starts, then translate the job's outcome (completed /
 * cancelled / rejected by an operation lock / genuinely failed) into the
 * matching {@link ExecutionProgressService} call. Per-chunk progress itself is
 * reported by {@link InventoryItemWriter}; by the time {@link #afterJob} runs,
 * the {@link Execution} row already carries the final
 * found/analyzed/cacheHits/errors counts. Performance instrumentation lives in
 * {@link InventoryTelemetryRecorder}.
 */
@Component
public class InventoryJobExecutionListener implements JobExecutionListener {

	private final ExecutionRepository executionRepository;
	private final ExecutionProgressService executionProgressService;
	private final ExecutionCancellationService executionCancellationService;
	private final FileScanner fileScanner;
	private final ScanExclusionService scanExclusionService;
	private final PhashBacklogAsyncRunner phashBacklogAsyncRunner;
	private final InventoryTelemetryRecorder telemetryRecorder;

	public InventoryJobExecutionListener(ExecutionRepository executionRepository,
			ExecutionProgressService executionProgressService,
			ExecutionCancellationService executionCancellationService, FileScanner fileScanner,
			ScanExclusionService scanExclusionService, PhashBacklogAsyncRunner phashBacklogAsyncRunner,
			InventoryTelemetryRecorder telemetryRecorder) {
		this.executionRepository = executionRepository;
		this.executionProgressService = executionProgressService;
		this.executionCancellationService = executionCancellationService;
		this.fileScanner = fileScanner;
		this.scanExclusionService = scanExclusionService;
		this.phashBacklogAsyncRunner = phashBacklogAsyncRunner;
		this.telemetryRecorder = telemetryRecorder;
	}

	@Override
	public void beforeJob(JobExecution jobExecution) {
		telemetryRecorder.reset();

		Long executionId = executionId(jobExecution);

		executionCancellationService.register(executionId);

		Execution execution = findExecution(executionId);

		var params = jobExecution.getJobParameters();

		var sourcePath = PathUtils.normalizePath(params.getString("sourcePath"));

		var scanOptions = new ScanOptions(Boolean.parseBoolean(params.getString("recursive")),
				Boolean.parseBoolean(params.getString("includeHidden")), List.of(),
				scanExclusionService.excludedExtensions(), scanExclusionService.excludedFolders());

		executionProgressService.updateStatus(execution, ExecutionStatus.SCANNING_FILES,
				ExecutionStepType.SCANNING_STARTED, ExecutionMessages.countingFiles());

		long scanStart = System.nanoTime();

		long total = fileScanner.count(sourcePath, scanOptions);

		telemetryRecorder.recordScanCount(System.nanoTime() - scanStart, total);

		executionProgressService.updateTotal(execution, (int) Math.min(total, Integer.MAX_VALUE));

		executionProgressService.updateStatus(execution, ExecutionStatus.PROCESSING_FILES,
				ExecutionStepType.PROCESSING_STARTED, ExecutionMessages.processingFiles());
	}

	@Override
	public void afterJob(JobExecution jobExecution) {
		Long executionId = executionId(jobExecution);

		boolean cancelled = executionCancellationService.isCancelled(executionId);

		executionCancellationService.unregister(executionId);

		Execution execution = findExecution(executionId);

		Snapshot metrics = telemetryRecorder.snapshot();

		if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
			int totalErrors = execution.getErrors() == null ? 0 : execution.getErrors();

			ExecutionStatus finalStatus = totalErrors > 0 ? ExecutionStatus.FINISHED_WITH_ERRORS
					: ExecutionStatus.FINISHED;

			executionProgressService.finish(execution, finalStatus, execution.getFilesFound(),
					execution.getFilesAnalyzed(), execution.getCacheHits(), totalErrors,
					ExecutionMessages.inventoryCompleted());
		} else if (cancelled) {
			executionProgressService.cancel(execution, ExecutionMessages.inventoryCancelled());
		} else {
			String detail = rootFailureMessage(jobExecution);

			executionProgressService.fail(execution,
					isLockRejection(jobExecution) ? ExecutionMessages.inventoryRejected(detail)
							: ExecutionMessages.inventoryFailed(detail));
		}

		telemetryRecorder.persist(executionId, metrics);

		// Inventory just added photos with no fingerprint; resume the backlog now that
		// no
		// inventory is active. start() is idempotent and guards against concurrent
		// runs.
		if (phashBacklogAsyncRunner.start()) {
			phashBacklogAsyncRunner.run();
		}
	}

	private Long executionId(JobExecution jobExecution) {
		return jobExecution.getJobParameters().getLong("executionId");
	}

	private Execution findExecution(Long executionId) {
		return executionRepository.findById(executionId)
				.orElseThrow(() -> new IllegalStateException("Execution not found: " + executionId));
	}

	private boolean isLockRejection(JobExecution jobExecution) {
		return jobExecution.getAllFailureExceptions().stream().anyMatch(this::containsLockException);
	}

	private boolean containsLockException(Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof OperationLockException) {
				return true;
			}
		}

		return false;
	}

	private String rootFailureMessage(JobExecution jobExecution) {
		return jobExecution.getAllFailureExceptions().stream().findFirst().map(Throwable::getMessage)
				.orElse("Unknown error.");
	}
}