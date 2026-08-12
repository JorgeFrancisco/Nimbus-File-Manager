package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.AcquiredBoundaries;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.GeoDatasetIdentity;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.GeoDatasetState;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.GeoDatasetStateRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;

/**
 * Turning acquired files into the installed dataset - the writing half of the
 * lifecycle, and the definition of when an installation is finished.
 *
 * <p>
 * <b>Why the order is the order.</b> Importing the levels, completing the
 * dissolved territories and publishing the downloaded files cannot share a
 * transaction: a territory the source has no data for must not roll back a
 * worldwide import, and files on disk are not transactional at all. So no single
 * commit here means "installed", and the last step exists to say it: the state
 * row is marked complete only after every other step returned.
 *
 * <p>
 * Anything that fails above that line leaves rows present but not complete,
 * which is precisely a state the next run repairs. It repairs it cheaply, too -
 * the files are already on disk and the source confirms them unchanged, so the
 * rebuild costs an import and not a download.
 */
@Component
public class GeoDatasetInstallation {

	private final WorkspaceManager workspaceManager;
	private final BoundarySource boundarySource;
	private final GeoJsonBoundaryImporter importer;
	private final BoundaryTerritoryCompletion territoryCompletion;
	private final BoundaryGeometryCache geometryCache;
	private final GeoDatasetStateRepository geoDatasetStateRepository;

	public GeoDatasetInstallation(WorkspaceManager workspaceManager, BoundarySource boundarySource,
			GeoJsonBoundaryImporter importer, BoundaryTerritoryCompletion territoryCompletion,
			BoundaryGeometryCache geometryCache, GeoDatasetStateRepository geoDatasetStateRepository) {
		this.workspaceManager = workspaceManager;
		this.boundarySource = boundarySource;
		this.importer = importer;
		this.territoryCompletion = territoryCompletion;
		this.geometryCache = geometryCache;
		this.geoDatasetStateRepository = geoDatasetStateRepository;
	}

	/** @return how many boundaries the installation put in place */
	public long install(AcquiredBoundaries acquired) {
		long imported = importer.importDataset(acquired.files(), identity());

		imported += territoryCompletion.complete(workspaceManager.geodata());

		// Everything imported: only now do the new files replace the ones resolution
		// reads. Until this line the previous dataset is still on disk, which is what
		// a failed update falls back to.
		boundarySource.commit(workspaceManager.geodata());

		// The in-memory geometry/availability cache now points at the previous
		// dataset (or at "empty"); drop it so resolution reads the fresh import.
		geometryCache.invalidate();

		geoDatasetStateRepository.markComplete(GeoDatasetState.SINGLETON_ID);

		return imported;
	}

	private GeoDatasetIdentity identity() {
		return new GeoDatasetIdentity(boundarySource.sourceTag(), boundarySource.version(),
				boundarySource.providerLabel(), boundarySource.license());
	}
}