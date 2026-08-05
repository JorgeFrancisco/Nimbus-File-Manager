package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.constants.MetadataMessages;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildPayload;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildRequest;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildResponse;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildSimulationResult;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * Re-reads the metadata of a folder that came off the queue, or says what such
 * a pass would change.
 *
 * <p>
 * Resumable, and not by checkpoint. The candidates are a query - the files of
 * the folder not analysed since the cutoff - and every batch this pass commits
 * stamps its files as analysed, so a second attempt asks again and finds only
 * what the first did not reach. There is nothing to remember because the work
 * describes itself.
 *
 * <p>
 * The ownership goes unused: this writes rows, never files. What keeps it from
 * running beside the work that would invalidate it is the inventory check, not
 * a lock over a tree.
 */
@Component
public class MetadataRebuildJobHandler implements ExecutionJobHandler {

	private final MetadataRebuildService metadataRebuildService;
	private final MetadataRebuildPreviewWriter metadataRebuildPreviewWriter;
	private final ExecutionProgressService executionProgressService;
	private final ExecutionCancellationService executionCancellationService;
	private final ExecutionPayloadCodec executionPayloadCodec;
	private final InventoryRunningState inventoryRunningState;

	public MetadataRebuildJobHandler(MetadataRebuildService metadataRebuildService,
			MetadataRebuildPreviewWriter metadataRebuildPreviewWriter,
			ExecutionProgressService executionProgressService,
			ExecutionCancellationService executionCancellationService, ExecutionPayloadCodec executionPayloadCodec,
			InventoryRunningState inventoryRunningState) {
		this.metadataRebuildService = metadataRebuildService;
		this.metadataRebuildPreviewWriter = metadataRebuildPreviewWriter;
		this.executionProgressService = executionProgressService;
		this.executionCancellationService = executionCancellationService;
		this.executionPayloadCodec = executionPayloadCodec;
		this.inventoryRunningState = inventoryRunningState;
	}

	@Override
	public ExecutionType type() {
		return ExecutionType.METADATA_REBUILD;
	}

	@Override
	public boolean resumable() {
		return true;
	}

	@Override
	public void handle(Execution execution, ClaimedExecution claimed, ExecutionOwnership ownership) {
		MetadataRebuildPayload payload = payloadOf(claimed);

		// An inventory is cataloguing the very files this would re-read, and the two
		// write the same rows. Stepping aside ends this row rather than leaving a
		// thread to try again; asking for it once more is a click away, and the folder
		// will still be there.
		if (inventoryRunningState.isRunning()) {
			executionProgressService.finishCommand(execution, ExecutionStatus.REJECTED, new ExecutionCounts(0, 0, 0, 0),
					MetadataMessages.deferred());

			return;
		}

		if (payload.dryRunValue()) {
			simulate(execution, payload);

			return;
		}

		rebuild(execution, payload);
	}

	/**
	 * A dry run writes no metadata and leaves what it found where the screen can
	 * read it - which is the whole reason it is a run at all rather than an answer
	 * to a request: it opens files with exiftool, and that belongs here.
	 */
	private void simulate(Execution execution, MetadataRebuildPayload payload) {
		MetadataRebuildSimulationResult result = metadataRebuildService.simulate(payload.toRequest());

		metadataRebuildPreviewWriter.write(execution.getId(), payload.sourcePath(), result);

		executionProgressService.finishCommand(execution, ExecutionStatus.FINISHED,
				new ExecutionCounts(result.candidates(), 0, 0, 0),
				MetadataMessages.simulated(result.candidates(), result.wouldChange()));
	}

	/**
	 * Only what the screen shows becomes durable. The pass also counts files
	 * skipped for having no location and files of a type it does not read, and
	 * those stay in the line it logs: a counter nobody displays is a column that
	 * ages without anyone noticing it went wrong.
	 */
	private void rebuild(Execution execution, MetadataRebuildPayload payload) {
		MetadataRebuildRequest request = payload.toRequest();

		executionProgressService.updateTotal(execution, (int) metadataRebuildService.countCandidates(request));

		MetadataRebuildResponse result = metadataRebuildService.rebuild(request,
				processed -> executionProgressService.updateLiveProgress(execution, 0, (int) processed, 0, 0,
						MetadataMessages.rebuilding()),
				() -> shouldStop(execution.getId()));

		executionProgressService.finishCommand(execution, outcome(execution, result),
				new ExecutionCounts(result.candidates(), result.rebuilt(), result.skippedMissing(), result.errors()),
				MetadataMessages.completed(result.candidates(), result.rebuilt(), result.skippedMissing(),
						result.errors()));
	}

	/**
	 * Two reasons to stop, both read from the database rather than from a field:
	 * the user cancelled, or an inventory started meanwhile. Either way the batches
	 * already committed stay committed.
	 */
	private boolean shouldStop(Long executionId) {
		return executionCancellationService.isCancelled(executionId) || inventoryRunningState.isRunning();
	}

	/**
	 * Stopping wins over failing: a pass that was told to stand down did not fail,
	 * it was interrupted, and what it already rebuilt stays rebuilt.
	 */
	private ExecutionStatus outcome(Execution execution, MetadataRebuildResponse result) {
		if (shouldStop(execution.getId())) {
			return ExecutionStatus.CANCELLED;
		}

		return result.errors() > 0 ? ExecutionStatus.FINISHED_WITH_ERRORS : ExecutionStatus.FINISHED;
	}

	/**
	 * A payload written in a shape this version does not know is refused rather
	 * than read as far as it goes: reading the dry-run flag wrong would write the
	 * catalog when the user asked to be shown what would happen.
	 */
	private MetadataRebuildPayload payloadOf(ClaimedExecution claimed) {
		MetadataRebuildPayload payload = executionPayloadCodec.decode(claimed.requestPayload(),
				MetadataRebuildPayload.class);

		if (payload.schemaVersion() == null
				|| payload.schemaVersion() != MetadataMessages.PAYLOAD_SCHEMA_VERSION) {
			throw new IllegalArgumentException("Metadata rebuild payload schema " + payload.schemaVersion()
					+ " cannot be run by this version, which writes and reads schema "
					+ MetadataMessages.PAYLOAD_SCHEMA_VERSION);
		}

		return payload;
	}
}