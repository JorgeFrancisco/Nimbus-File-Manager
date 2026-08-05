package br.com.jorgemelo.nimbusfilemanager.organization.application;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.organization.application.constants.OrganizationConstants;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationUndoPayload;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * Runs an undo that came off the queue.
 *
 * <p>
 * Not resumable, and for a sharper reason than the forward move: half the
 * reversal has already happened, and every file it put back is now a file the
 * movements still describe as needing to be put back. A second pass would find
 * them at their original paths, see the destination occupied, and record errors
 * for work that succeeded. What closes the gap is the movement rows, which are
 * marked one at a time as each file lands.
 */
@Component
public class OrganizationUndoJobHandler implements ExecutionJobHandler {

	private final OrganizationUndoService organizationUndoService;
	private final ExecutionPayloadCodec executionPayloadCodec;

	public OrganizationUndoJobHandler(OrganizationUndoService organizationUndoService,
			ExecutionPayloadCodec executionPayloadCodec) {
		this.organizationUndoService = organizationUndoService;
		this.executionPayloadCodec = executionPayloadCodec;
	}

	@Override
	public ExecutionType type() {
		return ExecutionType.UNDO;
	}

	@Override
	public void handle(Execution execution, ClaimedExecution claimed, ExecutionOwnership ownership) {
		organizationUndoService.undo(undoneExecutionId(claimed), execution, ownership);
	}

	private long undoneExecutionId(ClaimedExecution claimed) {
		OrganizationUndoPayload payload = executionPayloadCodec.decode(claimed.requestPayload(),
				OrganizationUndoPayload.class);

		if (payload.schemaVersion() == null
				|| payload.schemaVersion() != OrganizationConstants.UNDO_PAYLOAD_SCHEMA_VERSION) {
			throw new IllegalArgumentException("Undo payload schema " + payload.schemaVersion()
					+ " cannot be run by this version, which writes and reads schema "
					+ OrganizationConstants.UNDO_PAYLOAD_SCHEMA_VERSION);
		}

		if (payload.undoneExecutionId() == null) {
			throw new IllegalArgumentException("An undo has to name the execution it reverses");
		}

		return payload.undoneExecutionId();
	}
}