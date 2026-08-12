package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.GeoAdminBoundaryRepository;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.GeoDatasetStateRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import lombok.extern.slf4j.Slf4j;

/**
 * Uninstalling the dataset: the rows, the record of what they were, the cache
 * built from them and the files they came from.
 *
 * <p>
 * The state row goes with the boundaries deliberately. Leaving it behind would
 * leave a claim that a dataset is installed and complete over a table with
 * nothing in it - the one shape an update is entitled to skip work over.
 */
@Slf4j
@Component
public class GeoDatasetRemoval {

	private static final String DOWNLOADS = "downloads";

	private final WorkspaceManager workspaceManager;
	private final GeoAdminBoundaryRepository geoAdminBoundaryRepository;
	private final GeoDatasetStateRepository geoDatasetStateRepository;
	private final BoundaryGeometryCache geometryCache;

	public GeoDatasetRemoval(WorkspaceManager workspaceManager, GeoAdminBoundaryRepository geoAdminBoundaryRepository,
			GeoDatasetStateRepository geoDatasetStateRepository, BoundaryGeometryCache geometryCache) {
		this.workspaceManager = workspaceManager;
		this.geoAdminBoundaryRepository = geoAdminBoundaryRepository;
		this.geoDatasetStateRepository = geoDatasetStateRepository;
		this.geometryCache = geometryCache;
	}

	@Transactional
	public void remove() {
		geoAdminBoundaryRepository.deleteAllRows();

		geoDatasetStateRepository.deleteAllInBatch();

		geometryCache.invalidate();

		deleteRecursively(workspaceManager.geodata().resolve(DOWNLOADS));

		log.info("Geographic dataset removed");
	}

	private void deleteRecursively(Path folder) {
		if (!Files.exists(folder)) {
			return;
		}

		try (Stream<Path> paths = Files.walk(folder)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException e) {
					log.warn("Could not delete {}", path, e);
				}
			});
		} catch (IOException e) {
			log.warn("Could not clean folder {}", folder, e);
		}
	}
}