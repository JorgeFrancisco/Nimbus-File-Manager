package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import java.nio.file.Path;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestorePayload;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Puts quarantined files back where they came from, off the queue.
 *
 * <p>
 * Both shapes arrive here now: a whole selection, each item to its own origin,
 * and one file to a destination somebody chose. The conversation that produced
 * that choice happened before this row existed - a name collision and a missing
 * origin folder are questions for a person, and a worker has nobody to ask - so
 * what arrives is always an intention with nothing left to decide.
 *
 * <p>
 * Not resumable: files have already been moved out of quarantine by the time
 * anybody notices it stopped, and the movements it already reversed are marked
 * one at a time as each lands.
 */
@Component
public class QuarantineRestoreJobHandler implements ExecutionJobHandler {

	private final QuarantineService quarantineService;
	private final ExecutionPayloadCodec executionPayloadCodec;

	public QuarantineRestoreJobHandler(QuarantineService quarantineService,
			ExecutionPayloadCodec executionPayloadCodec) {
		this.quarantineService = quarantineService;
		this.executionPayloadCodec = executionPayloadCodec;
	}

	@Override
	public ExecutionType type() {
		return ExecutionType.QUARANTINE_RESTORE;
	}

	@Override
	public void handle(Execution execution, ClaimedExecution claimed, ExecutionOwnership ownership) {
		QuarantineRestorePayload payload = executionPayloadCodec.decode(claimed.requestPayload(),
				QuarantineRestorePayload.class);

		if (payload.schemaVersion() == null
				|| payload.schemaVersion() != QuarantineConstants.PAYLOAD_SCHEMA_VERSION) {
			throw new IllegalArgumentException("Quarantine restore payload schema " + payload.schemaVersion()
					+ " cannot be run by this version, which writes and reads schema "
					+ QuarantineConstants.PAYLOAD_SCHEMA_VERSION);
		}

		if (payload.movementIds() == null || payload.movementIds().isEmpty()) {
			throw new IllegalArgumentException("A quarantine restore has to name the items it puts back");
		}

		// A destination belongs to one file: it is the answer somebody gave about that
		// file, and applying it to a selection would put every one of them at the same
		// path, overwriting each other.
		if (payload.destination() != null && payload.movementIds().size() != 1) {
			throw new IllegalArgumentException("A decided destination restores exactly one item, not "
					+ payload.movementIds().size());
		}

		quarantineService.restoreMany(payload.movementIds(), destination(payload), execution, ownership);
	}

	private Path destination(QuarantineRestorePayload payload) {
		return payload.destination() == null ? null : PathUtils.normalizePath(payload.destination());
	}
}