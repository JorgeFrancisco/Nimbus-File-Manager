package br.com.jorgemelo.nimbusfilemanager.organization.application;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.constants.OrganizationConstants;
import br.com.jorgemelo.nimbusfilemanager.organization.application.constants.OrganizationMessages;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationUndoPayload;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

/**
 * Asking for an execution to be reversed.
 *
 * <p>
 * Whether the run can be undone at all is decided here, while somebody is
 * looking at the screen. A request that cannot be right should be refused with a
 * message, not become a row that fails in another process minutes later.
 *
 * <p>
 * The folders are the original's two ends, swapped: an undo moves from where
 * the files went back to where they came from, and those are the paths the
 * worker takes before it starts. They are not all of them - a duplicate
 * quarantined out of one folder goes back to wherever it originally lived - so
 * the reversal takes the rest for itself once it can read the movements.
 */
@Service
public class OrganizationUndoLauncherService extends LocalizedComponent {

	private final ExecutionEnqueueService executionEnqueueService;
	private final ExecutionPayloadCodec executionPayloadCodec;
	private final ExecutionMapper executionMapper;
	private final ExecutionMessageCodec executionMessageCodec;

	public OrganizationUndoLauncherService(ExecutionEnqueueService executionEnqueueService,
			ExecutionPayloadCodec executionPayloadCodec, ExecutionMapper executionMapper,
			ExecutionMessageCodec executionMessageCodec) {
		this.executionEnqueueService = executionEnqueueService;
		this.executionPayloadCodec = executionPayloadCodec;
		this.executionMapper = executionMapper;
		this.executionMessageCodec = executionMessageCodec;
	}

	private StatusMessage coded(ExecutionMessage message) {
		return StatusMessage.coded(message.code(), executionMessageCodec.encode(message.args()));
	}

	public ExecutionResponse launch(Execution undone) {
		// Both organization moves and duplicate quarantine moves are plain
		// source-to-target movements, so the same reversal undoes either. Nothing else
		// leaves movements to reverse.
		if (undone.getExecutionType() != ExecutionType.ORGANIZATION
				&& undone.getExecutionType() != ExecutionType.DEDUP_DELETE) {
			throw new IllegalArgumentException("Execution is not undoable: " + undone.getId());
		}

		Execution queued = Execution.builder().executionType(ExecutionType.UNDO)
				.sourcePath(undone.getTargetPath()).targetPath(undone.getSourcePath()).recursive(false)
				.executeFlag(true)
				.requestPayload(executionPayloadCodec.encode(new OrganizationUndoPayload(
						OrganizationConstants.UNDO_PAYLOAD_SCHEMA_VERSION, undone.getId())))
				.statusMessage(coded(OrganizationMessages.undoQueued())).build();

		return executionMapper.toResponse(executionEnqueueService.enqueue(queued)
				.orElseThrow(() -> new IllegalStateException("An undo was refused as a duplicate, and undos carry no "
						+ "deduplication key - so the row was refused for a reason nobody has described yet")));
	}
}