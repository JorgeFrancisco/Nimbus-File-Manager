package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.GeoDatasetProgress;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.OfflineGeoDataset;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.AcquiredBoundaries;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.OfflineGeoDatasetStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import lombok.extern.slf4j.Slf4j;

/**
 * The dataset lifecycle as the rest of the application asks for it: what is
 * installed, bring it up to date, remove it. Technology-neutral - it depends
 * only on the {@link BoundarySource} abstraction and never names a concrete data
 * source.
 *
 * <p>
 * <b>An update that finds nothing new does nothing.</b> The source says whether
 * any level actually changed, and when none did - and what is installed is
 * complete and has rows behind it - the run ends there. It used to parse roughly
 * a gigabyte of GeoJSON and replace every boundary in the database to arrive at
 * exactly the rows it started with, which cost the write-ahead log a full
 * rewrite of the table and stalled everything running beside it on the
 * checkpoints that followed.
 *
 * <p>
 * <b>Both conditions, never one.</b> Unchanged bytes say nothing about whether
 * the database still holds what they produced: a migration that reset the
 * dataset, a removal, or a run that died mid-installation all leave files whose
 * ETags still match and nothing to resolve against. {@link InstalledGeoDataset}
 * is what answers the other half, and the answer is asked of the boundaries
 * themselves rather than of anything that remembers them.
 */
@Slf4j
@Service
public class BoundaryDatasetManager implements OfflineGeoDataset {

	private final WorkspaceManager workspaceManager;
	private final BoundarySource boundarySource;
	private final InstalledGeoDataset installedGeoDataset;
	private final GeoDatasetInstallation installation;
	private final GeoDatasetRemoval removal;
	private final GeoDatasetProgress progress;

	public BoundaryDatasetManager(WorkspaceManager workspaceManager, BoundarySource boundarySource,
			InstalledGeoDataset installedGeoDataset, GeoDatasetInstallation installation, GeoDatasetRemoval removal,
			GeoDatasetProgress progress) {
		this.workspaceManager = workspaceManager;
		this.boundarySource = boundarySource;
		this.installedGeoDataset = installedGeoDataset;
		this.installation = installation;
		this.removal = removal;
		this.progress = progress;
	}

	@Override
	public OfflineGeoDatasetStatus status() {
		return installedGeoDataset.status();
	}

	@Override
	public boolean bringUpToDate() {
		try {
			AcquiredBoundaries acquired = boundarySource.fetch(workspaceManager.geodata());

			if (!acquired.changed() && installedGeoDataset.isUsable()) {
				// Nothing was staged, so there is nothing to publish and nothing to undo:
				// the files on disk are the ones the installation was built from.
				log.info("Geographic dataset is already current: every level was unchanged at the source and the"
						+ " installed dataset is complete, so nothing was imported");

				progress.alreadyUpToDate();

				return false;
			}

			installation.install(acquired);

			return true;
		} catch (RuntimeException e) {
			log.error("Geographic dataset download/import failed", e);

			// The import runs in its own transaction and rolled back, so the rows are
			// the previous ones; dropping the staged files puts the disk back in the
			// same state. Resolution keeps working on the dataset it already had.
			boundarySource.discard(workspaceManager.geodata());

			throw e;
		}
	}

	@Override
	public void remove() {
		removal.remove();
	}
}