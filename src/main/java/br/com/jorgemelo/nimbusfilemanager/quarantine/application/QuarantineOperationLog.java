package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionErrorService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

/**
 * The execution a quarantine operation runs under, and the per-file failures
 * that belong to it.
 *
 * <p>
 * Restoring and purging both act on the user's own files - one puts them back,
 * the other deletes them for good - so both are operations like any other and
 * belong on the executions screen with their own counters. Keeping that
 * bookkeeping in one place spares each service the dependencies it would
 * otherwise carry only to open and close a row, and keeps the two rows shaped
 * alike.
 */
@Component
public class QuarantineOperationLog extends LocalizedComponent {

	private final ExecutionRepository executionRepository;
	private final ExecutionErrorService executionErrorService;
	private final ExecutionProgressService executionProgressService;
	private final Clock clock;

	public QuarantineOperationLog(ExecutionRepository executionRepository, ExecutionErrorService executionErrorService,
			ExecutionProgressService executionProgressService, Clock clock) {
		this.executionRepository = executionRepository;
		this.executionErrorService = executionErrorService;
		this.executionProgressService = executionProgressService;
		this.clock = clock;
	}

	public Execution startRestore(int selected) {
		return start(ExecutionType.QUARANTINE_RESTORE, message("backend.quarantine.restoreStarted", selected),
				selected);
	}

	public Execution startPurge(int selected) {
		return start(ExecutionType.QUARANTINE_PURGE, message("backend.quarantine.purgeStarted", selected), selected);
	}

	/**
	 * Clearing records whose file is already gone deletes nothing from disk - it
	 * reconciles the catalog with what is actually there. Its own type, because on
	 * the executions screen it must not read as the purge that erases files.
	 */
	public Execution startAbsentCleanup(int selected) {
		return start(ExecutionType.QUARANTINE_CLEANUP, message("backend.quarantine.cleanupStarted", selected),
				selected);
	}

	private Execution start(ExecutionType executionType, String statusMessage, int selected) {
		Execution execution = Execution.builder().executionType(executionType).status(ExecutionStatus.STARTED)
				.startedAt(LocalDateTime.now(clock)).recursive(false).executeFlag(true)
				.statusMessage(StatusMessage.raw(statusMessage)).filesFound(selected).filesAnalyzed(0).cacheHits(0)
				.filesMoved(0).simulatedFiles(0).errors(0).build();

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
	 * operation currently running - an operation that crashed would leave a
	 * phantom running on every screen until someone edited the database. Delegated
	 * to the shared service because it commits in its own transaction: the caller's
	 * may be the very thing that just broke.
	 */
	public void fail(Execution execution, String detail) {
		executionProgressService.fail(execution, ExecutionMessages.operationFailed(detail));
	}

	public void recordFailure(Execution execution, Path file, ExecutionErrorType errorType, String errorMessage) {
		executionErrorService.save(file, errorType, errorMessage, execution);
	}

	/** Lets the shared classifier name the failure when there is an exception. */
	public void recordFailure(Execution execution, Path file, Exception failure) {
		executionErrorService.save(file, failure, execution);
	}
}