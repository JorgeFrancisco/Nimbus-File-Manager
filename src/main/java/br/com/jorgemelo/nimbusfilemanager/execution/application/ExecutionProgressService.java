package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.ExecutionStatusNames;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.ExecutionStep;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionStepRepository;

@Service
public class ExecutionProgressService {

	private final ExecutionRepository executionRepository;
	private final ExecutionStepRepository executionStepRepository;
	private final ExecutionCancellationService executionCancellationService;
	private final ExecutionMessageCodec messageCodec;
	private final Clock clock;

	public ExecutionProgressService(ExecutionRepository executionRepository,
			ExecutionStepRepository executionStepRepository, ExecutionCancellationService executionCancellationService,
			ExecutionMessageCodec messageCodec, Clock clock) {
		this.executionRepository = executionRepository;
		this.executionStepRepository = executionStepRepository;
		this.executionCancellationService = executionCancellationService;
		this.messageCodec = messageCodec;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void updateStatus(Execution execution, ExecutionStatus status, ExecutionStepType stepType,
			ExecutionMessage message) {
		Execution managed = findExecution(execution);

		managed.setStatus(status);

		applyMessage(managed, message);

		saveStep(managed, stepType, null, message);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void updateProgress(Execution execution, int filesFound, int filesAnalyzed, int cacheHits, int errors,
			Path currentFile) {
		Execution managed = findExecution(execution);

		managed.setFilesFound(filesFound);
		managed.setFilesAnalyzed(filesAnalyzed);
		managed.setCacheHits(cacheHits);
		managed.setErrors(errors);

		ExecutionMessage message = currentFile == null ? ExecutionMessages.progressUpdated()
				: ExecutionMessages.processingFile(currentFile);

		applyMessage(managed, message);

		saveStep(managed, ExecutionStepType.PROGRESS_UPDATED, currentFile, message);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void updateProgress(Execution execution, int filesFound, int filesAnalyzed, int cacheHits, int errors,
			String currentItem) {
		Execution managed = findExecution(execution);

		managed.setFilesFound(filesFound);
		managed.setFilesAnalyzed(filesAnalyzed);
		managed.setCacheHits(cacheHits);
		managed.setErrors(errors);

		ExecutionMessage message = currentItem == null ? ExecutionMessages.progressUpdated()
				: ExecutionMessages.processing(currentItem);

		applyMessage(managed, message);

		saveStep(managed, ExecutionStepType.PROGRESS_UPDATED, null, message);
	}

	/**
	 * Lightweight, high-frequency progress refresh used <em>while</em> a chunk is
	 * still being processed, so the live progress screen advances smoothly instead
	 * of freezing between chunk commits. It updates the execution row's counters
	 * and message but records <strong>no</strong> {@link ExecutionStep}: the
	 * per-chunk {@link #updateProgress} keeps the durable step trail, so these
	 * granular updates never flood the steps table.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void updateLiveProgress(Execution execution, int filesFound, int filesAnalyzed, int cacheHits, int errors,
			ExecutionMessage message) {
		Execution managed = findExecution(execution);

		managed.setFilesFound(filesFound);
		managed.setFilesAnalyzed(filesAnalyzed);
		managed.setCacheHits(cacheHits);
		managed.setErrors(errors);

		applyMessage(managed, message);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void updateTotal(Execution execution, int totalExpected) {
		Execution managed = findExecution(execution);

		managed.setTotalExpected(totalExpected);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void finish(Execution execution, ExecutionStatus status, int filesFound, int filesAnalyzed, int cacheHits,
			int errors, ExecutionMessage message) {
		Execution managed = findExecution(execution);

		managed.setStatus(status);
		managed.setFinishedAt(LocalDateTime.now(clock));
		managed.setFilesFound(filesFound);
		managed.setFilesAnalyzed(filesAnalyzed);
		managed.setCacheHits(cacheHits);
		managed.setErrors(errors);

		applyMessage(managed, message);

		saveStep(managed, ExecutionStepType.FINISHED, null, message);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void cancel(Execution execution, ExecutionMessage message) {
		Execution managed = findExecution(execution);

		managed.setStatus(ExecutionStatus.CANCELLED);
		managed.setFinishedAt(LocalDateTime.now(clock));

		applyMessage(managed, message);

		saveStep(managed, ExecutionStepType.CANCELLED, null, message);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void fail(Execution execution, ExecutionMessage message) {
		Execution managed = findExecution(execution);

		managed.setStatus(ExecutionStatus.ERROR);
		managed.setFinishedAt(LocalDateTime.now(clock));

		applyMessage(managed, message);

		saveStep(managed, ExecutionStepType.ERROR, null, message);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordError(Execution execution, Path file, String errorDetail, int filesFound, int filesAnalyzed,
			int cacheHits, int errors) {
		Execution managed = findExecution(execution);

		managed.setFilesFound(filesFound);
		managed.setFilesAnalyzed(filesAnalyzed);
		managed.setCacheHits(cacheHits);
		managed.setErrors(errors);

		String path = file == null ? null : file.toAbsolutePath().normalize().toString();

		if (path == null) {
			// No path to identify the file: keep the raw error text as a legacy message,
			// there is no stable code for an arbitrary failure string.
			managed.setStatusMessage(StatusMessage.raw(errorDetail));
		} else {
			applyMessage(managed, ExecutionMessages.errorProcessingFile(path));
		}

		// The step keeps the raw, dynamic error text verbatim (not a catalog message).
		saveStepRaw(managed, ExecutionStepType.FILE_ERROR, file, errorDetail);
	}

	/**
	 * Marks executions that were left "running" in the database
	 * (STARTED/SCANNING/PROCESSING with no finish) as INTERRUPTED - but only the
	 * ones that are genuinely orphaned. An execution whose background thread is
	 * still alive in this JVM (see {@link ExecutionCancellationService#isLive}) is
	 * skipped: it is still running and still holding its operation lock, so marking
	 * it INTERRUPTED would lie about its state and leave new runs on the same path
	 * rejected as "already running". This is why running an inventory (which calls
	 * this to clean up crashed runs) no longer kills the status of a concurrent
	 * organization. On startup the live-thread map is empty, so every stale
	 * execution from a previous process is correctly marked.
	 */
	@Transactional
	public void markInterruptedExecutions() {
		List<Execution> executions = executionRepository
				.findByFinishedAtIsNullAndStatusIn(ExecutionStatusNames.IN_PROGRESS);

		for (Execution execution : executions) {
			if (executionCancellationService.isLive(execution.getId())) {
				// Still running in this JVM - leave its real status alone.
				continue;
			}

			ExecutionMessage message = ExecutionMessages.executionInterrupted();

			execution.setStatus(ExecutionStatus.INTERRUPTED);
			execution.setFinishedAt(LocalDateTime.now(clock));

			applyMessage(execution, message);

			saveStep(execution, ExecutionStepType.INTERRUPTED, null, message);
		}
	}

	/**
	 * Stores a stable message code plus its typed args on the execution and clears
	 * the legacy free-text {@code message}, so new rows are never identified by
	 * their text. Shared with call sites that finish the execution themselves (e.g.
	 * the organization executor) so the persisted shape stays identical everywhere.
	 */
	public void applyMessage(Execution execution, ExecutionMessage message) {
		execution.setStatusMessage(StatusMessage.coded(message.code(), messageCodec.encode(message.args())));
	}

	private Execution findExecution(Execution execution) {
		return executionRepository.findById(execution.getId())
				.orElseThrow(() -> new IllegalStateException("Execution not found: " + execution.getId()));
	}

	private void saveStep(Execution execution, ExecutionStepType stepType, Path path, ExecutionMessage message) {
		executionStepRepository.save(ExecutionStep.builder().execution(execution).stepType(stepType)
				.path(path == null ? null : path.toAbsolutePath().normalize().toString())
				.statusMessage(StatusMessage.coded(message.code(), messageCodec.encode(message.args())))
				.filesFound(execution.getFilesFound()).filesAnalyzed(execution.getFilesAnalyzed())
				.cacheHits(execution.getCacheHits()).errors(execution.getErrors()).build());
	}

	private void saveStepRaw(Execution execution, ExecutionStepType stepType, Path path, String rawMessage) {
		executionStepRepository.save(ExecutionStep.builder().execution(execution).stepType(stepType)
				.path(path == null ? null : path.toAbsolutePath().normalize().toString())
				.statusMessage(StatusMessage.raw(rawMessage)).filesFound(execution.getFilesFound())
				.filesAnalyzed(execution.getFilesAnalyzed()).cacheHits(execution.getCacheHits())
				.errors(execution.getErrors()).build());
	}
}