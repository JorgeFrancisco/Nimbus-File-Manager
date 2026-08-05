package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionErrorService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

/**
 * How a quarantine operation closes its execution, and the per-file failures
 * that belong to it.
 *
 * <p>
 * Restoring and purging both act on the user's own files - one puts them back,
 * the other deletes them for good - so both are operations like any other and
 * belong on the executions screen with their own counters. Keeping that
 * bookkeeping in one place spares each service the dependencies it would
 * otherwise carry only to close a row, and keeps the rows shaped alike.
 *
 * <p>
 * Nothing here opens one any more. Every quarantine operation now arrives
 * claimed from the queue, so the row exists before the work does - which is the
 * difference between an execution somebody can find while it runs and one that
 * appears only after it ended.
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

	/**
	 * Closes the row with what actually happened. {@code skipped} carries the items
	 * that stayed in quarantine waiting for a decision (a name collision, a missing
	 * origin folder) - they are not failures, so counting them as errors would make
	 * a restore that needs one click look broken.
	 */
	public void finish(Execution execution, int selected, int restored, int skipped, int errors,
			ExecutionMessage message) {
		close(execution, errors == 0 ? ExecutionStatus.FINISHED : ExecutionStatus.FINISHED_WITH_ERRORS, selected,
				restored, skipped, errors, message);
	}

	/**
	 * Closes a run that stopped before its last item - cancelled by whoever asked
	 * for it, or standing on locks it no longer holds. Everything it counted really
	 * happened; the row says how far it got instead of claiming the whole selection
	 * was seen.
	 */
	public void stop(Execution execution, ExecutionStatus status, int selected, int restored, int skipped, int errors,
			ExecutionMessage message) {
		close(execution, status, selected, restored, skipped, errors, message);
	}

	private void close(Execution execution, ExecutionStatus status, int selected, int restored, int skipped, int errors,
			ExecutionMessage message) {
		Execution managed = executionRepository.findById(execution.getId()).orElse(execution);

		managed.setStatus(status);
		managed.setFinishedAt(LocalDateTime.now(clock));
		managed.setFilesFound(selected);
		managed.setFilesAnalyzed(restored + skipped + errors);
		managed.setFilesMoved(restored);
		managed.setCacheHits(skipped);
		managed.setErrors(errors);

		executionProgressService.applyMessage(managed, message);

		executionRepository.save(managed);
	}

	/**
	 * Closes a row whose loop died on the way. Without this the execution keeps
	 * {@code finishedAt} null, and the application reads any such row as the
	 * operation currently running - an operation that crashed would leave a phantom
	 * running on every screen until someone edited the database. Delegated to the
	 * shared service because it commits in its own transaction: the caller's may be
	 * the very thing that just broke.
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