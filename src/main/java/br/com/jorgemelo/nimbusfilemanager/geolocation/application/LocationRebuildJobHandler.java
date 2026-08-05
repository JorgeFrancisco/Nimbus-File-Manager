package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.InventoryRunningState;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.constants.GeoMessages;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LocationRebuildPayload;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LocationRebuildResult;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * Resolves the location of media that already has coordinates, off the queue.
 *
 * <p>
 * Resumable, and not by checkpoint. The candidates are a query - what the scope
 * describes - and resolving one produces the same answer every time, so a
 * second attempt asks again and finds what the first did not reach. Nothing to
 * remember, because the work describes itself.
 *
 * <p>
 * The ownership goes unused: this writes rows, never files. What it does need
 * from the lock is the other half - holding the geodata path is what keeps a
 * dataset update from replacing the boundaries it is reading.
 */
@Component
public class LocationRebuildJobHandler implements ExecutionJobHandler {

	private final LocationRebuildService locationRebuildService;
	private final ExecutionProgressService executionProgressService;
	private final ExecutionCancellationService executionCancellationService;
	private final ExecutionPayloadCodec executionPayloadCodec;
	private final InventoryRunningState inventoryRunningState;

	public LocationRebuildJobHandler(LocationRebuildService locationRebuildService,
			ExecutionProgressService executionProgressService,
			ExecutionCancellationService executionCancellationService, ExecutionPayloadCodec executionPayloadCodec,
			InventoryRunningState inventoryRunningState) {
		this.locationRebuildService = locationRebuildService;
		this.executionProgressService = executionProgressService;
		this.executionCancellationService = executionCancellationService;
		this.executionPayloadCodec = executionPayloadCodec;
		this.inventoryRunningState = inventoryRunningState;
	}

	@Override
	public ExecutionType type() {
		return ExecutionType.LOCATION_REBUILD;
	}

	@Override
	public boolean resumable() {
		return true;
	}

	@Override
	public void handle(Execution execution, ClaimedExecution claimed, ExecutionOwnership ownership) {
		LocationRebuildPayload payload = payloadOf(claimed);

		// An inventory resolves locations as it catalogues, so rewriting the same
		// answers under it would have the two disagreeing about the same media.
		// Stepping aside ends this row; asking again is a click away.
		if (inventoryRunningState.isRunning()) {
			executionProgressService.finishCommand(ownership, ExecutionStatus.REJECTED, new ExecutionCounts(0, 0, 0, 0),
					GeoMessages.deferred());

			return;
		}

		executionProgressService.updateTotal(ownership,
				(int) locationRebuildService.countCandidates(payload.scope()));

		LocationRebuildResult result = locationRebuildService.rebuild(payload.scope(),
				processed -> executionProgressService.updateLiveProgress(ownership, 0, (int) processed, 0, 0,
						GeoMessages.rebuilding()),
				() -> shouldStop(execution.getId()));

		finish(execution, ownership, result);
	}

	private boolean shouldStop(Long executionId) {
		return executionCancellationService.isCancelled(executionId) || inventoryRunningState.isRunning();
	}

	/**
	 * An unresolved medium is not an error: it has coordinates that fall outside
	 * every boundary the dataset knows - the open sea, or a country the levels do
	 * not cover. It is counted apart for exactly that reason, and only the errors
	 * decide the outcome.
	 */
	private void finish(Execution execution, ExecutionOwnership ownership, LocationRebuildResult result) {
		ExecutionStatus status = outcome(execution, result);

		executionProgressService.finishCommand(ownership, status,
				new ExecutionCounts((int) result.candidates(), (int) result.resolved(), (int) result.unresolved(),
						(int) result.errors()),
				GeoMessages.rebuilt(result.candidates(), result.resolved(), result.unresolved(), result.errors()));
	}

	private ExecutionStatus outcome(Execution execution, LocationRebuildResult result) {
		if (shouldStop(execution.getId())) {
			return ExecutionStatus.CANCELLED;
		}

		return result.errors() > 0 ? ExecutionStatus.FINISHED_WITH_ERRORS : ExecutionStatus.FINISHED;
	}

	private LocationRebuildPayload payloadOf(ClaimedExecution claimed) {
		LocationRebuildPayload payload = executionPayloadCodec.decode(claimed.requestPayload(),
				LocationRebuildPayload.class);

		if (payload.schemaVersion() == null || payload.schemaVersion() != GeoMessages.PAYLOAD_SCHEMA_VERSION
				|| payload.scope() == null) {
			throw new IllegalArgumentException("Location rebuild payload schema " + payload.schemaVersion()
					+ " cannot be run by this version, which writes and reads schema "
					+ GeoMessages.PAYLOAD_SCHEMA_VERSION);
		}

		return payload;
	}
}