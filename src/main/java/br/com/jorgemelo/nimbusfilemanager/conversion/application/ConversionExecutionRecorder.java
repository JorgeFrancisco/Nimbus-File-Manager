package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionTotals;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionErrorService;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

/**
 * Writes the {@code CONVERSION} execution the history screen shows, keeping
 * that bookkeeping out of the conversion logic itself. The counters reuse the
 * existing execution columns so the shared history, detail and statistics
 * screens render a conversion without knowing anything about it:
 * {@code filesMoved} counts converted files and {@code cacheHits} the skipped
 * ones, exactly as the duplicate removal already does.
 */
@Component
public class ConversionExecutionRecorder extends LocalizedComponent {

	private final ExecutionRepository executionRepository;
	private final ExecutionErrorService executionErrorService;
	private final Clock clock;

	public ConversionExecutionRecorder(ExecutionRepository executionRepository,
			ExecutionErrorService executionErrorService, Clock clock) {
		this.executionRepository = executionRepository;
		this.executionErrorService = executionErrorService;
		this.clock = clock;
	}

	public Execution start(Path folder, int total) {
		String path = folder == null ? null : folder.toString();

		Execution execution = Execution.builder().executionType(ExecutionType.CONVERSION)
				.status(ExecutionStatus.STARTED).startedAt(LocalDateTime.now(clock)).sourcePath(path).targetPath(path)
				.recursive(false).executeFlag(true)
				.statusMessage(StatusMessage.raw(message("backend.conversion.started", total))).filesFound(total)
				.filesAnalyzed(0).cacheHits(0).filesMoved(0).simulatedFiles(0).errors(0).build();

		return executionRepository.save(execution);
	}

	/**
	 * Names the file a batch could not convert. The execution counter alone left
	 * the screen reporting "1 error" over an empty list, with no way to tell which
	 * of three hundred videos it was.
	 */
	public void recordFailure(Execution execution, Path file, String reason) {
		executionErrorService.save(file, ExecutionErrorType.CONVERSION_ERROR, reason, execution);
	}

	/**
	 * Closes a row whose batch died on the way. An execution still holding a null
	 * {@code finishedAt} is read everywhere as the operation currently running, and
	 * a conversion is the longest of them: the phantom would sit on every screen
	 * until the next restart swept it.
	 */
	public void fail(Execution execution, String detail) {
		Execution managed = executionRepository.findById(execution.getId()).orElse(execution);

		managed.setStatus(ExecutionStatus.ERROR);
		managed.setFinishedAt(LocalDateTime.now(clock));
		managed.setStatusMessage(StatusMessage.raw(message("backend.execution.operationFailed", detail)));

		executionRepository.save(managed);

		execution.setStatus(managed.getStatus());
		execution.setFinishedAt(managed.getFinishedAt());
	}

	public void finish(Execution execution, ConversionTotals totals, String message, boolean cancelled) {
		Execution managed = executionRepository.findById(execution.getId()).orElse(execution);

		managed.setStatus(status(totals, cancelled));
		managed.setFinishedAt(LocalDateTime.now(clock));
		managed.setFilesFound(totals.total());
		managed.setFilesAnalyzed(totals.total());
		managed.setFilesMoved(totals.converted());
		managed.setCacheHits(totals.skipped());
		managed.setErrors(totals.errors());
		managed.setStatusMessage(StatusMessage.raw(message));

		executionRepository.save(managed);

		execution.setStatus(managed.getStatus());
		execution.setFinishedAt(managed.getFinishedAt());
	}

	/**
	 * A batch the user stopped is CANCELLED even when everything it did manage to
	 * convert worked: the history has to show it did not run to the end.
	 */
	private ExecutionStatus status(ConversionTotals totals, boolean cancelled) {
		if (cancelled) {
			return ExecutionStatus.CANCELLED;
		}

		return totals.errors() > 0 ? ExecutionStatus.FINISHED_WITH_ERRORS : ExecutionStatus.FINISHED;
	}
}