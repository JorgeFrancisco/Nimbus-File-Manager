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
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.GeoDatasetPayload;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * Brings the boundary dataset up to date, off the queue.
 *
 * <p>
 * Resumable, and safely so, because of a protocol that already existed: the
 * acquisition stages its files beside the published ones, the import runs in its
 * own transaction, and only a successful import publishes what was staged. A run
 * that dies anywhere in between leaves the previous dataset exactly as it was -
 * on disk and in the database - so running again from the start is
 * indistinguishable from having run once.
 *
 * <p>
 * The ownership goes unused: what this needs from the lock is that it holds the
 * geodata path, which is what keeps a location rebuild from reading boundaries
 * while they are being replaced.
 */
@Component
public class GeoDatasetJobHandler implements ExecutionJobHandler {

	private final OfflineGeoDataset offlineGeoDataset;
	private final MediaLocationService mediaLocationService;
	private final GeoDatasetProgress geoDatasetProgress;
	private final ExecutionProgressService executionProgressService;
	private final ExecutionCancellationService executionCancellationService;
	private final ExecutionPayloadCodec executionPayloadCodec;
	private final InventoryRunningState inventoryRunningState;

	public GeoDatasetJobHandler(OfflineGeoDataset offlineGeoDataset, MediaLocationService mediaLocationService,
			GeoDatasetProgress geoDatasetProgress, ExecutionProgressService executionProgressService,
			ExecutionCancellationService executionCancellationService, ExecutionPayloadCodec executionPayloadCodec,
			InventoryRunningState inventoryRunningState) {
		this.offlineGeoDataset = offlineGeoDataset;
		this.mediaLocationService = mediaLocationService;
		this.geoDatasetProgress = geoDatasetProgress;
		this.executionProgressService = executionProgressService;
		this.executionCancellationService = executionCancellationService;
		this.executionPayloadCodec = executionPayloadCodec;
		this.inventoryRunningState = inventoryRunningState;
	}

	@Override
	public ExecutionType type() {
		return ExecutionType.GEO_DATASET_UPDATE;
	}

	@Override
	public boolean resumable() {
		return true;
	}

	@Override
	public void handle(Execution execution, ClaimedExecution claimed, ExecutionOwnership ownership) {
		payloadOf(claimed);

		// An inventory reads locations as it catalogues; replacing the dataset under
		// it would change the answers halfway through. It ends the row rather than
		// blocking a thread, and the next daily pass asks again.
		if (inventoryRunningState.isRunning()) {
			executionProgressService.finishCommand(ownership, ExecutionStatus.REJECTED, new ExecutionCounts(0, 0, 0, 0),
					GeoMessages.deferred());

			return;
		}

		// The one phase of a dataset update that can be stopped, and deliberately the
		// only one. Past this line the acquisition is under way: the fetch stages
		// hundreds of megabytes, the import commits in a transaction of its own, and
		// only a successful import promotes what was staged. Stopping between the
		// import and that promotion would leave the tables describing one version and
		// the files on disk another - the single state this protocol exists to make
		// impossible - so nothing here asks again. A worker that dies mid-update is
		// already safe by the same construction, which is why the type is resumable.
		if (executionCancellationService.isCancelled(execution.getId())) {
			executionProgressService.finishCommand(ownership, ExecutionStatus.CANCELLED,
					new ExecutionCounts(0, 0, 0, 0), GeoMessages.updateCancelled());

			return;
		}

		geoDatasetProgress.attach(ownership);

		try {
			offlineGeoDataset.downloadAndImport();

			// A new dataset version invalidates previous resolutions; clear the
			// persistent cache so rebuilds resolve against fresh data.
			mediaLocationService.clearCache();

			long records = geoDatasetProgress.recordsImported();

			executionProgressService.finishCommand(ownership, ExecutionStatus.FINISHED,
					new ExecutionCounts((int) records, (int) records, 0, 0), GeoMessages.updated(records));
		} finally {
			geoDatasetProgress.detach();
		}
	}

	/**
	 * A payload written in a shape this version does not know is refused rather
	 * than run anyway: what a future one adds could be the source to fetch from,
	 * and fetching from the wrong one replaces the dataset with somebody else's.
	 */
	private void payloadOf(ClaimedExecution claimed) {
		GeoDatasetPayload payload = executionPayloadCodec.decode(claimed.requestPayload(), GeoDatasetPayload.class);

		if (payload.schemaVersion() == null || payload.schemaVersion() != GeoMessages.PAYLOAD_SCHEMA_VERSION) {
			throw new IllegalArgumentException("Geographic dataset payload schema " + payload.schemaVersion()
					+ " cannot be run by this version, which writes and reads schema "
					+ GeoMessages.PAYLOAD_SCHEMA_VERSION);
		}
	}
}