package br.com.jorgemelo.nimbusfilemanager.execution.application;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.EtaEstimate;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionStepResponse;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.model.ExecutionStep;
import br.com.jorgemelo.nimbusfilemanager.shared.application.ExecutionLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
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
	private final ExecutionProgressReader progress;
	private final EtaEstimator etaEstimator;

	public ExecutionMapper(ExecutionMessageCodec codec, ExecutionLabels executionLabels,
			ExecutionProgressReader progress, EtaEstimator etaEstimator) {
		this.codec = codec;
		this.executionLabels = executionLabels;
		this.progress = progress;
		this.etaEstimator = etaEstimator;
	}

	public ExecutionResponse toResponse(Execution execution) {
		if (execution == null) {
			return null;
		}

		ExecutionStatus status = execution.getStatus();

		return new ExecutionResponse(UuidV7.orLegacy(execution.getExecutionPublicId(), execution.getId()),
				execution.getExecutionType().name(), status.name(), phaseName(execution.getPhase()),
				execution.getStartedAt(), execution.getFinishedAt(),
				execution.getSourcePath(), execution.getTargetPath(), execution.getFilesFound(),
				execution.getFilesAnalyzed(), execution.getCacheHits(), execution.getFilesMoved(),
				execution.getSimulatedFiles(), execution.getErrors(), execution.getTotalExpected(),
				percentComplete(execution), currentItemPercent(execution), eta(execution),
				resolve(execution.getStatusMessage()),
				execution.getExecuteFlag(),
				executionLabels.status(status), status.isTerminal(), executionLabels.type(execution.getExecutionType()),
				triggerLabel(execution.getTriggerEvent()), DateTimeFormatUtils.human(execution.getStartedAt()),
				DateTimeFormatUtils.human(execution.getFinishedAt()));
	}

	ExecutionStepResponse toStepResponse(ExecutionStep step) {
		return new ExecutionStepResponse(UuidV7.orLegacy(step.getExecutionStepPublicId(), step.getId()),
				UuidV7.orLegacy(step.getExecution().getExecutionPublicId(), step.getExecution().getId()),
				step.getStepType().name(), step.getPath(), resolve(step.getStatusMessage()), step.getFilesFound(),
				step.getFilesAnalyzed(), step.getCacheHits(), step.getErrors(), step.getCreatedAt());
	}

	/**
	 * Resolves a stored message to localized text. When a stable code is present it
	 * is looked up in the request locale with its typed args; otherwise the legacy
	 * free-text message is returned verbatim (older rows predate the code).
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
	// The phase as a technical name, never a translated one: the screen decides
	// whether to show an ETA by comparing it, and comparing translated text is
	// what breaks the moment someone switches language.
	private String phaseName(ExecutionPhase phase) {
		return phase == null ? null : phase.name();
	}

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

	/**
	 * How far into the current item, and only while there is one.
	 *
	 * <p>
	 * This is where the guarantee lives that a stale percentage never reaches a
	 * screen. A worker that died leaves its row RUNNING until the reclaim finds it,
	 * but it also stops renewing anything - and every other way a row leaves
	 * RUNNING clears the column on the way out. Answering null for anything that is
	 * not running means the bar can only ever show a number somebody is still
	 * producing. Package-private for the same reason as
	 * {@link #percentComplete(Execution)}: the banner draws this guarantee too,
	 * and a second copy of it is a second chance to get it wrong.
	 */
	Integer currentItemPercent(Execution execution) {
		return execution.getStatus() == ExecutionStatus.RUNNING ? execution.getCurrentItemPercent() : null;
	}

	/**
	 * Package-private rather than private because the activity banner shows the
	 * same percentage as the execution screen, and it has to be the same number.
	 * A second copy of this arithmetic is a second answer waiting to disagree -
	 * one rounding, the other not, the same work reading differently depending on
	 * where the user happened to look.
	 */
	Double percentComplete(Execution execution) {
		return progress.percent(execution);
	}

	/**
	 * How much longer, as a fact rather than a sentence.
	 *
	 * <p>
	 * Only while the row is RUNNING, for the same reason the item percentage is:
	 * a worker that died leaves its row RUNNING until the reclaim finds it, but a
	 * row in any other state is not being measured by anybody, and an estimate
	 * nobody is producing is a stale number wearing a live one's clothes.
	 */
	EtaEstimate eta(Execution execution) {
		return execution.getStatus() == ExecutionStatus.RUNNING ? etaEstimator.estimate(execution)
				: EtaEstimate.notApplicable();
	}
}