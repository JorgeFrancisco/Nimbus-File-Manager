package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.EligibilityAnnouncer;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence.SimilarityPurgeWriter;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantinePurgePayload;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantinePurgeResult;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * Expunges quarantined files for good, off the queue.
 *
 * <p>
 * Two ways in and one loop out: the daily pass names a retention window and the
 * purge decides what is overdue when it actually runs, and a person names the
 * items directly. Deciding the overdue set here rather than at queueing time is
 * deliberate - a request that waited behind a long conversion must not expunge
 * by yesterday's clock.
 *
 * <p>
 * Never resumable. This is the one operation with nothing to undo.
 */
@Component
public class QuarantinePurgeJobHandler implements ExecutionJobHandler {

	private final QuarantinePurgeService quarantinePurgeService;
	private final ExecutionPayloadCodec executionPayloadCodec;
	private final SimilarityPurgeWriter similarityPurgeWriter;
	private final EligibilityAnnouncer eligibilityAnnouncer;

	public QuarantinePurgeJobHandler(QuarantinePurgeService quarantinePurgeService,
			ExecutionPayloadCodec executionPayloadCodec, SimilarityPurgeWriter similarityPurgeWriter,
			EligibilityAnnouncer eligibilityAnnouncer) {
		this.quarantinePurgeService = quarantinePurgeService;
		this.executionPayloadCodec = executionPayloadCodec;
		this.similarityPurgeWriter = similarityPurgeWriter;
		this.eligibilityAnnouncer = eligibilityAnnouncer;
	}

	@Override
	public ExecutionType type() {
		return ExecutionType.QUARANTINE_PURGE;
	}

	@Override
	public void handle(Execution execution, ClaimedExecution claimed, ExecutionOwnership ownership) {
		QuarantinePurgePayload payload = executionPayloadCodec.decode(claimed.requestPayload(),
				QuarantinePurgePayload.class);

		if (payload.schemaVersion() == null || payload.schemaVersion() != QuarantineConstants.PAYLOAD_SCHEMA_VERSION) {
			throw new IllegalArgumentException("Quarantine purge payload schema " + payload.schemaVersion()
					+ " cannot be run by this version, which writes and reads schema "
					+ QuarantineConstants.PAYLOAD_SCHEMA_VERSION);
		}

		if (payload.movementIds() != null && !payload.movementIds().isEmpty()) {
			forgotten(quarantinePurgeService.purgeSelected(payload.movementIds(), execution, ownership));

			return;
		}

		if (payload.retentionDays() == null || payload.retentionDays() <= 0) {
			throw new IllegalArgumentException("A quarantine purge has to name either the items or a window");
		}

		forgotten(quarantinePurgeService.purgeOlderThan(payload.retentionDays(), execution, ownership));
	}

	/**
	 * Once for the run, never once per file. The files are destroyed for good, so a
	 * published analysis that still names them describes a library that no longer
	 * exists - and the set the next analysis runs over has changed, which is said
	 * through the same announcement every other mutation of it uses. A pass that
	 * freed no catalogue row has nothing to say.
	 */
	private void forgotten(QuarantinePurgeResult result) {
		if (result.catalogsFreed() > 0) {
			similarityPurgeWriter.forgetPurgedFiles();

			eligibilityAnnouncer.announce("hard purge");
		}
	}
}