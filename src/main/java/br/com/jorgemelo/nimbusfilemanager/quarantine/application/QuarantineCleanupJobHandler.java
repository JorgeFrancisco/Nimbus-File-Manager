package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineCleanupPayload;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * Clearing the records of quarantined files that are no longer there, off the
 * queue.
 *
 * <p>
 * Its own type rather than the purge's: on the executions screen this must not
 * read as the operation that erases files, because it erases none - it
 * reconciles the catalog with a folder somebody else emptied.
 *
 * <p>
 * Resumable, and it is the rare operation that honestly is: it deletes rows for
 * files that were already gone, so a second pass over the same shortlist finds
 * the work either done or still to do, and never does it twice.
 */
@Component
public class QuarantineCleanupJobHandler implements ExecutionJobHandler {

	private final QuarantinePurgeService quarantinePurgeService;
	private final ExecutionPayloadCodec executionPayloadCodec;

	public QuarantineCleanupJobHandler(QuarantinePurgeService quarantinePurgeService,
			ExecutionPayloadCodec executionPayloadCodec) {
		this.quarantinePurgeService = quarantinePurgeService;
		this.executionPayloadCodec = executionPayloadCodec;
	}

	@Override
	public ExecutionType type() {
		return ExecutionType.QUARANTINE_CLEANUP;
	}

	@Override
	public boolean resumable() {
		return true;
	}

	@Override
	public void handle(Execution execution, ClaimedExecution claimed, ExecutionOwnership ownership) {
		QuarantineCleanupPayload payload = executionPayloadCodec.decode(claimed.requestPayload(),
				QuarantineCleanupPayload.class);

		if (payload.schemaVersion() == null
				|| payload.schemaVersion() != QuarantineConstants.PAYLOAD_SCHEMA_VERSION) {
			throw new IllegalArgumentException("Quarantine cleanup payload schema " + payload.schemaVersion()
					+ " cannot be run by this version, which writes and reads schema "
					+ QuarantineConstants.PAYLOAD_SCHEMA_VERSION);
		}

		if (payload.movementIds() == null || payload.movementIds().isEmpty()) {
			throw new IllegalArgumentException("A quarantine cleanup has to name the records it clears");
		}

		quarantinePurgeService.cleanupAbsent(payload.movementIds(), execution, ownership);
	}
}