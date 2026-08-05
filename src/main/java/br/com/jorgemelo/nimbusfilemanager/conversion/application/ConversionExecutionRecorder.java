package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionFileResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.constants.ConversionMessages;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionTotals;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.model.ConversionItemResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.repository.ConversionItemResultRepository;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionErrorService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
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
	private final ConversionItemResultRepository conversionItemResultRepository;
	private final ExecutionProgressService executionProgressService;
	private final Clock clock;

	public ConversionExecutionRecorder(ExecutionRepository executionRepository,
			ExecutionErrorService executionErrorService,
			ConversionItemResultRepository conversionItemResultRepository,
			ExecutionProgressService executionProgressService, Clock clock) {
		this.executionRepository = executionRepository;
		this.executionErrorService = executionErrorService;
		this.conversionItemResultRepository = conversionItemResultRepository;
		this.executionProgressService = executionProgressService;
		this.clock = clock;
	}

	/**
	 * Keeps one line of the report the screen shows when the batch ends.
	 *
	 * <p>
	 * Written as each file is decided rather than collected and saved at the end,
	 * for the reason the whole migration exists: a batch that runs for hours in
	 * another process has to be readable while it runs, and a crash halfway must
	 * leave behind what it did rather than nothing at all.
	 */
	public void recordItem(Execution execution, ConversionFileResult item) {
		conversionItemResultRepository.save(ConversionItemResult.builder().execution(execution)
				.mediaPublicId(item.mediaId()).fileName(item.fileName()).outcome(item.outcome())
				.originalBytes(item.originalBytes()).convertedBytes(item.convertedBytes()).message(item.message())
				.audioFallback(item.adjustments().audioFallback())
				.subtitlesDropped(item.adjustments().subtitlesDropped())
				.dataDropped(item.adjustments().dataDropped()).originalQuarantined(item.originalQuarantined())
				.build());
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
		executionProgressService.applyMessage(managed, ConversionMessages.failed(detail));

		executionRepository.save(managed);

		execution.setStatus(managed.getStatus());
		execution.setFinishedAt(managed.getFinishedAt());
	}

	public void finish(Execution execution, ConversionTotals totals, ExecutionMessage message, boolean cancelled) {
		Execution managed = executionRepository.findById(execution.getId()).orElse(execution);

		managed.setStatus(status(totals, cancelled));
		managed.setFinishedAt(LocalDateTime.now(clock));
		managed.setFilesFound(totals.total());
		managed.setFilesAnalyzed(totals.total());
		managed.setFilesMoved(totals.converted());
		managed.setCacheHits(totals.skipped());
		managed.setErrors(totals.errors());

		executionProgressService.applyMessage(managed, message);

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