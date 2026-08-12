package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.OfflineGeoDatasetStatus;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.GeoDatasetState;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.GeoDatasetStateRepository;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.GeoAdminBoundaryRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * What is installed right now - the reading half of the dataset lifecycle, kept
 * apart from the half that installs.
 *
 * <p>
 * Two questions live here and they are not the same one. {@link #status()}
 * describes the installation for a screen; {@link #isUsable()} decides whether
 * an update may skip its work, and that decision is the reason this class is
 * separate. It has to consult three things that can disagree - a state row, the
 * boundaries themselves, and whether the run that wrote them ever finished - and
 * a caller allowed to check only the convenient one is how a dataset that does
 * not exist gets reported as up to date.
 */
@Component
@Transactional(readOnly = true)
public class InstalledGeoDataset {

	private final WorkspaceManager workspaceManager;
	private final GeoDatasetStateRepository geoDatasetStateRepository;
	private final GeoAdminBoundaryRepository geoAdminBoundaryRepository;

	public InstalledGeoDataset(WorkspaceManager workspaceManager, GeoDatasetStateRepository geoDatasetStateRepository,
			GeoAdminBoundaryRepository geoAdminBoundaryRepository) {
		this.workspaceManager = workspaceManager;
		this.geoDatasetStateRepository = geoDatasetStateRepository;
		this.geoAdminBoundaryRepository = geoAdminBoundaryRepository;
	}

	/**
	 * Whether what is installed can be resolved against and therefore may be left
	 * alone by an update that found nothing new.
	 *
	 * <p>
	 * <b>All three, every time.</b> A state row that says complete is not enough:
	 * a migration that reset the dataset, a manual removal, or a database restored
	 * from before the import all leave a claim with nothing behind it. The count is
	 * what refuses those, and it is asked of the boundaries rather than remembered
	 * anywhere - a remembered count is the fact that goes stale first.
	 */
	public boolean isUsable() {
		return geoDatasetStateRepository.findInstalled().filter(GeoDatasetState::isComplete)
				.filter(_ -> geoAdminBoundaryRepository.count() > 0).isPresent();
	}

	/**
	 * The installation as the settings screen shows it. Nothing here is stored
	 * twice: the identity comes from the state row, the record count from the
	 * boundaries and the size from the folder they were built from.
	 */
	public OfflineGeoDatasetStatus status() {
		String directory = PathUtils.normalize(workspaceManager.geodata());

		Optional<GeoDatasetState> installed = geoDatasetStateRepository.findInstalled()
				.filter(GeoDatasetState::isComplete);

		if (installed.isEmpty()) {
			return OfflineGeoDatasetStatus.unavailable(directory);
		}

		long records = geoAdminBoundaryRepository.count();

		if (records == 0) {
			return OfflineGeoDatasetStatus.unavailable(directory);
		}

		GeoDatasetState state = installed.get();

		return new OfflineGeoDatasetStatus(true, state.getDatasetVersion(), records,
				folderSize(workspaceManager.geodata()), state.getImportedAt(), directory, state.getProvider(),
				state.getLicense());
	}

	private long folderSize(Path folder) {
		if (!Files.isDirectory(folder)) {
			return 0;
		}

		try (Stream<Path> paths = Files.walk(folder)) {
			return paths.filter(Files::isRegularFile).mapToLong(path -> {
				try {
					return Files.size(path);
				} catch (IOException _) {
					return 0;
				}
			}).sum();
		} catch (IOException _) {
			return 0;
		}
	}
}