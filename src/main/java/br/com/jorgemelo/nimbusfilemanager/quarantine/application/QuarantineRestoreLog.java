package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionErrorService;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

/**
 * The execution a quarantine restore runs under, and the per-file failures that
 * belong to it.
 *
 * <p>
 * A restore moves user files back into the library, so it is an operation like
 * any other and belongs on the executions screen with its own counters. Keeping
 * that bookkeeping here spares {@link QuarantineService} three dependencies it
 * would otherwise carry only to open and close a row.
 */
@Component
public class QuarantineRestoreLog extends LocalizedComponent {

	private final ExecutionRepository executionRepository;
	private final ExecutionErrorService executionErrorService;
	private final Clock clock;

	public QuarantineRestoreLog(ExecutionRepository executionRepository, ExecutionErrorService executionErrorService,
			Clock clock) {
		this.executionRepository = executionRepository;
		this.executionErrorService = executionErrorService;
		this.clock = clock;
	}

	public Execution start(int selected) {
		Execution execution = Execution.builder().executionType(ExecutionType.QUARANTINE_RESTORE)
				.status(ExecutionStatus.STARTED).startedAt(LocalDateTime.now(clock)).recursive(false).executeFlag(true)
				.statusMessage(StatusMessage.raw(message("backend.quarantine.restoreStarted", selected)))
				.filesFound(selected).filesAnalyzed(0).cacheHits(0).filesMoved(0).simulatedFiles(0).errors(0).build();

		return executionRepository.save(execution);
	}

	/**
	 * Closes the row with what actually happened. {@code skipped} carries the
	 * items that stayed in quarantine waiting for a decision (a name collision, a
	 * missing origin folder) - they are not failures, so counting them as errors
	 * would make a restore that needs one click look broken.
	 */
	public void finish(Execution execution, int selected, int restored, int skipped, int errors, String message) {
		Execution managed = executionRepository.findById(execution.getId()).orElse(execution);

		managed.setStatus(errors == 0 ? ExecutionStatus.FINISHED : ExecutionStatus.FINISHED_WITH_ERRORS);
		managed.setFinishedAt(LocalDateTime.now(clock));
		managed.setFilesFound(selected);
		managed.setFilesAnalyzed(selected);
		managed.setFilesMoved(restored);
		managed.setCacheHits(skipped);
		managed.setErrors(errors);
		managed.setStatusMessage(StatusMessage.raw(message));

		executionRepository.save(managed);
	}

	/**
	 * Closes a row whose loop died on the way. Without this the execution keeps
	 * {@code finishedAt} null, and the application reads any such row as the
	 * operation currently running - a restore that crashed would leave a phantom
	 * operation on every screen until someone edited the database.
	 */
	public void fail(Execution execution, int selected, String message) {
		Execution managed = executionRepository.findById(execution.getId()).orElse(execution);

		managed.setStatus(ExecutionStatus.ERROR);
		managed.setFinishedAt(LocalDateTime.now(clock));
		managed.setFilesFound(selected);
		managed.setErrors(selected - managed.getFilesMoved());
		managed.setStatusMessage(StatusMessage.raw(message));

		executionRepository.save(managed);
	}

	public void recordFailure(Execution execution, Path file, ExecutionErrorType errorType, String errorMessage) {
		executionErrorService.save(file, errorType, errorMessage, execution);
	}

	/** Lets the shared classifier name the failure when there is an exception. */
	public void recordFailure(Execution execution, Path file, Exception failure) {
		executionErrorService.save(file, failure, execution);
	}
}