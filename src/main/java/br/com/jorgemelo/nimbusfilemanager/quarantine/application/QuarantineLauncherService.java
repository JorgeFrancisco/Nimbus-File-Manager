package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineMessages;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantinePurgePayload;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestorePayload;
import br.com.jorgemelo.nimbusfilemanager.settings.application.QuarantineFolderPolicy;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionTrigger;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Asking for the quarantine work that belongs to the worker.
 *
 * <p>
 * Two of the three quarantine operations come through here. Restoring a whole
 * selection and expunging files - by the day's retention or by a person's pick -
 * move or delete the user's data in bulk and outlive the request that asked for
 * them. Restoring a single file does not: it is a conversation about one file
 * and stays where the question is asked.
 *
 * <p>
 * The quarantine folder goes in both path columns. It is the tree every one of
 * these touches, and it is what the worker takes before it starts; the origin
 * each restored file goes back to is locked by the restore itself, once it can
 * read where that is.
 *
 * <p>
 * Which is also why nothing is queued when that folder is not configured. The
 * work is defined by a tree, so the worker has to hold it, so a row that names
 * none cannot run - the dispatcher refuses it, correctly and loudly, and the
 * person is left with a failed execution for something that was impossible the
 * moment they clicked. The request is refused here instead, where there is
 * still somebody to tell.
 */
@Service
public class QuarantineLauncherService extends LocalizedComponent {

	private final QuarantineFolderPolicy quarantineFolderPolicy;
	private final QuarantineRetentionPolicy quarantineRetentionPolicy;
	private final ExecutionEnqueueService executionEnqueueService;
	private final ExecutionPayloadCodec executionPayloadCodec;
	private final ExecutionMessageCodec executionMessageCodec;
	private final ExecutionMapper executionMapper;

	public QuarantineLauncherService(QuarantineFolderPolicy quarantineFolderPolicy,
			QuarantineRetentionPolicy quarantineRetentionPolicy, ExecutionEnqueueService executionEnqueueService,
			ExecutionPayloadCodec executionPayloadCodec, ExecutionMessageCodec executionMessageCodec,
			ExecutionMapper executionMapper) {
		this.quarantineFolderPolicy = quarantineFolderPolicy;
		this.quarantineRetentionPolicy = quarantineRetentionPolicy;
		this.executionEnqueueService = executionEnqueueService;
		this.executionPayloadCodec = executionPayloadCodec;
		this.executionMessageCodec = executionMessageCodec;
		this.executionMapper = executionMapper;
	}

	public ExecutionResponse launchRestore(List<UUID> movementIds) {
		// No destination: a selection puts every item back at its own origin, which is
		// the only answer that needs nobody to have been asked anything.
		QuarantineRestorePayload payload = new QuarantineRestorePayload(QuarantineConstants.PAYLOAD_SCHEMA_VERSION,
				List.copyOf(movementIds), null);

		return enqueue(ExecutionType.QUARANTINE_RESTORE, movementIds.size(), payload,
				QuarantineMessages.restoreStarted(movementIds.size()), null);
	}

	public ExecutionResponse launchPurge(List<UUID> movementIds) {
		QuarantinePurgePayload payload = new QuarantinePurgePayload(QuarantineConstants.PAYLOAD_SCHEMA_VERSION, null,
				List.copyOf(movementIds));

		return enqueue(ExecutionType.QUARANTINE_PURGE, movementIds.size(), payload,
				QuarantineMessages.purgeStarted(movementIds.size()), null);
	}

	/**
	 * The daily pass. It names the window rather than the items: what is overdue
	 * has to be decided when the purge runs, not when it is queued, or a request
	 * waiting behind a long conversion would expunge by yesterday's clock. Only
	 * <em>whether</em> anything is overdue is asked here, so a quiet day leaves no
	 * row on the executions screen.
	 */
	public Optional<ExecutionResponse> launchScheduledPurge(int retentionDays) {
		// Asked before anything else, and answered with silence rather than with the
		// refusal below: nobody is watching a timer, and a daily pass that raised an
		// exception over a folder the user never set would be an error every day for
		// a feature they are not using.
		if (quarantineFolderPolicy.root().isEmpty() || !quarantineRetentionPolicy.hasOverdue(retentionDays)) {
			return Optional.empty();
		}

		QuarantinePurgePayload payload = new QuarantinePurgePayload(QuarantineConstants.PAYLOAD_SCHEMA_VERSION,
				retentionDays, null);

		return Optional
				.of(enqueue(ExecutionType.QUARANTINE_PURGE, 0, payload, QuarantineMessages.purgeStarted(0),
						ExecutionTrigger.TIMER));
	}

	/**
	 * @throws IllegalArgumentException when the quarantine folder is not
	 * configured, carrying the sentence to show: there is no tree for the worker
	 * to hold, so there is no request to make
	 */
	private ExecutionResponse enqueue(ExecutionType type, int selected, Object payload, ExecutionMessage started,
			ExecutionTrigger trigger) {
		String root = quarantineFolderPolicy.root().map(PathUtils::normalize)
				.orElseThrow(() -> new IllegalArgumentException(message(QuarantineMessages.FOLDER_NOT_CONFIGURED)));

		Execution queued = Execution.builder().executionType(type).triggerEvent(trigger).sourcePath(root)
				.targetPath(root).recursive(false).executeFlag(true).filesFound(selected)
				.requestPayload(executionPayloadCodec.encode(payload))
				.statusMessage(StatusMessage.coded(started.code(), executionMessageCodec.encode(started.args())))
				.build();

		return executionMapper.toResponse(executionEnqueueService.enqueue(queued)
				.orElseThrow(() -> new IllegalStateException("A quarantine request was refused as a duplicate, and "
						+ "these carry no deduplication key - so it was refused for a reason nobody has described")));
	}
}