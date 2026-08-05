package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import java.util.Optional;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.constants.GeoMessages;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.GeoDatasetPayload;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LocationRebuildPayload;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationRebuildScope;
import br.com.jorgemelo.nimbusfilemanager.shared.application.BackgroundWorkGate;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Asking for the two things that touch the geographic dataset.
 *
 * <p>
 * One launcher for both because what they have in common is what matters: they
 * declare the same path, and that is what makes them exclude each other. Writing
 * that path in two places would be writing the exclusion in two places.
 */
@Service
public class GeoLauncher {

	private final ExecutionEnqueueService executionEnqueueService;
	private final ExecutionPayloadCodec executionPayloadCodec;
	private final BackgroundWorkGate backgroundWorkGate;
	private final WorkspaceManager workspaceManager;

	public GeoLauncher(ExecutionEnqueueService executionEnqueueService, ExecutionPayloadCodec executionPayloadCodec,
			BackgroundWorkGate backgroundWorkGate, WorkspaceManager workspaceManager) {
		this.executionEnqueueService = executionEnqueueService;
		this.executionPayloadCodec = executionPayloadCodec;
		this.backgroundWorkGate = backgroundWorkGate;
		this.workspaceManager = workspaceManager;
	}

	/**
	 * Asks for a location rebuild, or joins the one already waiting for the same
	 * scope.
	 */
	public Optional<Execution> rebuildLocations(LocationRebuildScope scope) {
		return enqueue(ExecutionType.LOCATION_REBUILD, scope.name(),
				executionPayloadCodec
						.encode(new LocationRebuildPayload(GeoMessages.PAYLOAD_SCHEMA_VERSION, scope)),
				GeoMessages.REBUILD_QUEUED);
	}

	/**
	 * Asks for the dataset to be brought up to date, or joins the request already
	 * waiting.
	 *
	 * <p>
	 * One key for every asker: the daily timer, the button and a second click are
	 * the same request, because what they ask for is a state ("current") rather
	 * than an action to be repeated.
	 */
	public Optional<Execution> updateDataset() {
		return enqueue(ExecutionType.GEO_DATASET_UPDATE, "update",
				executionPayloadCodec.encode(new GeoDatasetPayload(GeoMessages.PAYLOAD_SCHEMA_VERSION)),
				GeoMessages.UPDATE_QUEUED);
	}

	/**
	 * The path both types declare, and the whole of the exclusion between them.
	 *
	 * <p>
	 * It is a real folder rather than a made-up key: the dataset lives there, the
	 * worker's advisory lock is taken over exactly the paths an execution names,
	 * and nothing else in the application works under it - so a rebuild and an
	 * update wait for each other while an inventory, a conversion or a
	 * fingerprint drain carry on untouched.
	 */
	private Optional<Execution> enqueue(ExecutionType type, String dedupKey, String payload, String messageCode) {
		// Queueing while the application is closing writes a row nothing will claim
		// before the pool goes. Nothing is lost: the dataset is still there to be
		// asked about, and the timer asks again.
		if (backgroundWorkGate.standDown()) {
			return Optional.empty();
		}

		Execution queued = Execution.builder().executionType(type)
				.sourcePath(PathUtils.normalize(workspaceManager.geodata())).recursive(false).executeFlag(true)
				.dedupKey(dedupKey).requestPayload(payload).statusMessage(StatusMessage.code(messageCode)).build();

		return Optional.of(executionEnqueueService.enqueueOrExisting(queued));
	}
}