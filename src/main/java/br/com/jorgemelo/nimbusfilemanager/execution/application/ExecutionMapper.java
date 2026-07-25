package br.com.jorgemelo.nimbusfilemanager.execution.application;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionStepResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.application.ExecutionLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.ExecutionStep;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.DateTimeFormatUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

/**
 * Builds the API responses for executions and their steps, resolving the stable
 * message code + typed args into localized text in the current request locale.
 * This is the single read-side localization point: emission stores only a code
 * and raw args (see {@link ExecutionMessages}); here they become the text the
 * user sees. Legacy rows without a code fall back to the persisted free-text
 * {@code message} verbatim - no regex or text matching.
 */
@Component
public class ExecutionMapper extends LocalizedComponent {

	private final ExecutionMessageCodec codec;
	private final ExecutionLabels executionLabels;

	public ExecutionMapper(ExecutionMessageCodec codec, ExecutionLabels executionLabels) {
		this.codec = codec;
		this.executionLabels = executionLabels;
	}

	public ExecutionResponse toResponse(Execution execution) {
		if (execution == null) {
			return null;
		}

		ExecutionStatus status = execution.getStatus();

		return new ExecutionResponse(UuidV7.orLegacy(execution.getPublicId(), execution.getId()),
				execution.getExecutionType().name(), status.name(), execution.getStartedAt(),
				execution.getFinishedAt(), execution.getSourcePath(), execution.getTargetPath(),
				execution.getFilesFound(), execution.getFilesAnalyzed(), execution.getCacheHits(),
				execution.getFilesMoved(), execution.getSimulatedFiles(), execution.getErrors(),
				execution.getTotalExpected(), percentComplete(execution),
				resolve(execution.getStatusMessage()),
				execution.getExecuteFlag(), executionLabels.status(status), status.isTerminal(),
				executionLabels.type(execution.getExecutionType()), triggerLabel(execution.getTriggerEvent()),
				DateTimeFormatUtils.human(execution.getStartedAt()),
				DateTimeFormatUtils.human(execution.getFinishedAt()));
	}

	ExecutionStepResponse toStepResponse(ExecutionStep step) {
		return new ExecutionStepResponse(UuidV7.orLegacy(step.getPublicId(), step.getId()),
				UuidV7.orLegacy(step.getExecution().getPublicId(), step.getExecution().getId()),
				step.getStepType().name(), step.getPath(),
				resolve(step.getStatusMessage()), step.getFilesFound(),
				step.getFilesAnalyzed(), step.getCacheHits(), step.getErrors(), step.getCreatedAt());
	}

	/**
	 * Resolves a stored message to localized text. When a stable code is present
	 * it is looked up in the request locale with its typed args; otherwise the
	 * legacy free-text message is returned verbatim (older rows predate the code).
	 */
	private String resolve(StatusMessage statusMessage) {
		if (statusMessage == null || statusMessage.getCode() == null) {
			return statusMessage == null ? null : statusMessage.getText();
		}

		return message(statusMessage.getCode(), codec.decode(statusMessage.getArgs()));
	}

	/**
	 * Localized label for what triggered the execution. Null for legacy rows that
	 * predate the trigger column, so the read side simply shows nothing.
	 */
	private String triggerLabel(ExecutionTrigger trigger) {
		if (trigger == null) {
			return null;
		}

		return switch (trigger) {
		case MANUAL -> message("backend.execution.trigger.MANUAL");
		case FILE_EVENT -> message("backend.execution.trigger.FILE_EVENT");
		case TIMER -> message("backend.execution.trigger.TIMER");
		};
	}

	private Double percentComplete(Execution execution) {
		Integer total = execution.getTotalExpected();
		Integer processed = execution.getFilesFound();

		if (total == null || total <= 0 || processed == null) {
			return null;
		}

		double percent = (processed * 100.0) / total;

		return Math.round(Math.min(percent, 100.0) * 10.0) / 10.0;
	}
}