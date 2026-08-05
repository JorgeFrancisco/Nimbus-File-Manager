package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateDeletePayload;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * Sends the duplicates a request named to quarantine, off the queue.
 *
 * <p>
 * Not resumable, and for the reason the default exists: files have already been
 * moved out of the library by the time anybody notices it stopped. Running it
 * again is harmless in practice - a file already in quarantine is no longer
 * active and is skipped - but that is the deletion being careful, not the queue
 * being allowed to repeat a mutation, and the two must not be confused.
 */
@Component
public class DuplicateDeletionJobHandler implements ExecutionJobHandler {

	private final DuplicateDeletionService duplicateDeletionService;
	private final ExecutionPayloadCodec executionPayloadCodec;

	public DuplicateDeletionJobHandler(DuplicateDeletionService duplicateDeletionService,
			ExecutionPayloadCodec executionPayloadCodec) {
		this.duplicateDeletionService = duplicateDeletionService;
		this.executionPayloadCodec = executionPayloadCodec;
	}

	@Override
	public ExecutionType type() {
		return ExecutionType.DEDUP_DELETE;
	}

	@Override
	public void handle(Execution execution, ClaimedExecution claimed, ExecutionOwnership ownership) {
		duplicateDeletionService.delete(payloadOf(claimed).publicIds(), execution, ownership);
	}

	private DuplicateDeletePayload payloadOf(ClaimedExecution claimed) {
		DuplicateDeletePayload payload = executionPayloadCodec.decode(claimed.requestPayload(),
				DuplicateDeletePayload.class);

		if (payload.schemaVersion() == null
				|| payload.schemaVersion() != DuplicateConstants.DELETE_PAYLOAD_SCHEMA_VERSION) {
			throw new IllegalArgumentException("Duplicate deletion payload schema " + payload.schemaVersion()
					+ " cannot be run by this version, which writes and reads schema "
					+ DuplicateConstants.DELETE_PAYLOAD_SCHEMA_VERSION);
		}

		if (payload.publicIds() == null || payload.publicIds().isEmpty()) {
			throw new IllegalArgumentException("A duplicate deletion has to name the files it removes");
		}

		return payload;
	}
}