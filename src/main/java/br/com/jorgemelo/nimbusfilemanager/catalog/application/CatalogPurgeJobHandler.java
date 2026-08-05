package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import java.util.OptionalInt;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.constants.CatalogConstants;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.constants.CatalogMessages;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.CatalogPurgePayload;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import lombok.extern.slf4j.Slf4j;

/**
 * Forgetting catalog entries whose file has been missing longer than the
 * retention window, off the queue.
 *
 * <p>
 * The row it closes is the whole point of the migration. This deletes rows the
 * user can never get back - years of extracted metadata, perceptual hashes,
 * resolved locations - and until now it happened on a timer inside the
 * application, leaving nothing on any screen. Now it is an operation like the
 * others: it says when it ran and how much it removed.
 *
 * <p>
 * Resumable. It removes rows that match a condition; running it again removes
 * what is left matching, which is the same end state.
 */
@Slf4j
@Component
public class CatalogPurgeJobHandler implements ExecutionJobHandler {

	private final CatalogFileRetentionService catalogFileRetentionService;
	private final ExecutionProgressService executionProgressService;
	private final ExecutionPayloadCodec executionPayloadCodec;

	public CatalogPurgeJobHandler(CatalogFileRetentionService catalogFileRetentionService,
			ExecutionProgressService executionProgressService, ExecutionPayloadCodec executionPayloadCodec) {
		this.catalogFileRetentionService = catalogFileRetentionService;
		this.executionProgressService = executionProgressService;
		this.executionPayloadCodec = executionPayloadCodec;
	}

	@Override
	public ExecutionType type() {
		return ExecutionType.CATALOG_PURGE;
	}

	/**
	 * What it deletes are catalog rows past their retention, chosen by age rather
	 * than by place. It holds the catalog port and not the file one, so there is
	 * no tree for it to exclude anybody from.
	 */
	@Override
	public boolean requiresPathLock() {
		return false;
	}

	@Override
	public boolean resumable() {
		return true;
	}

	/**
	 * Nothing on disk is touched, but what this deletes from the catalog cannot be
	 * got back, so the taking travels into the transaction that deletes it. The
	 * checkpoint here only saves the round trip; the delete answers to the pin.
	 */
	@Override
	public void handle(Execution execution, ClaimedExecution claimed, ExecutionOwnership ownership) {
		CatalogPurgePayload payload = executionPayloadCodec.decode(claimed.requestPayload(), CatalogPurgePayload.class);

		if (payload.schemaVersion() == null || payload.schemaVersion() != CatalogConstants.PAYLOAD_SCHEMA_VERSION) {
			throw new IllegalArgumentException("Catalog purge payload schema " + payload.schemaVersion()
					+ " cannot be run by this version, which writes and reads schema "
					+ CatalogConstants.PAYLOAD_SCHEMA_VERSION);
		}

		int days = payload.retentionDays() == null ? 0 : payload.retentionDays();

		if (!ownership.takingIsStillCurrent()) {
			log.info("Execution {} was replaced before its purge began, so none was started", execution.getId());

			return;
		}

		// The window is applied now, against the clock of now. Whoever queued this
		// only established that there was probably something to do.
		OptionalInt removed = catalogFileRetentionService.purgeMissingOlderThan(days, ownership);

		if (removed.isEmpty()) {
			// The row moved on between the checkpoint and the delete. Nothing was
			// removed, and this run has no outcome to write about a row it no longer
			// holds - the taking that replaced it will write its own.
			log.info("Execution {} lost its turn before the purge could run, so nothing was removed",
					execution.getId());

			return;
		}

		int purged = removed.getAsInt();

		executionProgressService.finishCommand(ownership, ExecutionStatus.FINISHED,
				new ExecutionCounts(purged, purged, 0, 0),
				purged == 0 ? CatalogMessages.purgeFoundNothing() : CatalogMessages.purgeCompleted(purged));
	}
}