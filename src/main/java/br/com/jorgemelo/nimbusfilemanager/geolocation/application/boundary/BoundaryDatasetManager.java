package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.OfflineGeoDataset;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.BoundaryMetadata;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LeveledBoundaryFile;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.OfflineGeoDatasetStatus;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.AdminBoundaryKind;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.GeoAdminBoundaryRepository;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates the administrative-boundary dataset lifecycle inside
 * workspace/geodata (downloads/, metadata.json): acquisition, import, removal
 * and status. Technology-neutral - it depends only on the
 * {@link BoundarySource} abstraction and never names a concrete data source.
 * Resolution reads from the database through {@link BoundaryGeometryCache}, so
 * every import or removal invalidates that cache.
 */
@Slf4j
@Service
public class BoundaryDatasetManager implements OfflineGeoDataset {

	private static final String DOWNLOADS = "downloads";

	private final WorkspaceManager workspaceManager;
	private final BoundarySource boundarySource;
	private final GeoJsonBoundaryImporter importer;
	private final BoundaryMetadataStore metadataStore;
	private final GeoAdminBoundaryRepository repository;
	private final BoundaryGeometryCache geometryCache;
	private final AppSettingService appSettingService;
	private final Clock clock;

	public BoundaryDatasetManager(WorkspaceManager workspaceManager, BoundarySource boundarySource,
			GeoJsonBoundaryImporter importer, BoundaryMetadataStore metadataStore,
			GeoAdminBoundaryRepository repository, BoundaryGeometryCache geometryCache,
			AppSettingService appSettingService, Clock clock) {
		this.workspaceManager = workspaceManager;
		this.boundarySource = boundarySource;
		this.importer = importer;
		this.metadataStore = metadataStore;
		this.repository = repository;
		this.geometryCache = geometryCache;
		this.appSettingService = appSettingService;
		this.clock = clock;
	}

	@Override
	public OfflineGeoDatasetStatus status() {
		String directory = PathUtils.normalize(workspaceManager.geodata());

		Optional<BoundaryMetadata> metadata = metadataStore.read();

		if (metadata.isEmpty()) {
			return OfflineGeoDatasetStatus.unavailable(directory, null);
		}

		BoundaryMetadata current = metadata.get();

		// If nothing is actually imported (e.g. a stale metadata file left by a
		// previous provider), report "not installed" with no misleading counts.
		if (repository.count() == 0) {
			return OfflineGeoDatasetStatus.unavailable(directory, current.getLastError());
		}

		boolean available = current.getImportedRecords() > 0 && current.getImportedAt() != null;

		return new OfflineGeoDatasetStatus(available, current.getVersion(), current.getImportedRecords(),
				current.getSizeBytes(), current.getDownloadedAt(), current.getImportedAt(), directory,
				current.getLastError(), current.getProvider(), current.getLicense());
	}

	@Override
	public OfflineGeoDatasetStatus downloadAndImport() {
		try {
			LocalDateTime downloadedAt = LocalDateTime.now(clock);

			List<LeveledBoundaryFile> files = boundarySource.fetch(workspaceManager.geodata());

			long imported = importer.importDataset(files, boundarySource.sourceTag(), boundarySource.version());

			imported += completeMissingTerritories();

			// The in-memory geometry/availability cache now points at the previous
			// dataset (or at "empty"); drop it so resolution reads the fresh import.
			geometryCache.invalidate();

			long sizeBytes = folderSize(workspaceManager.geodata());

			metadataStore.write(BoundaryMetadata.builder().provider(boundarySource.providerLabel())
					.license(boundarySource.license()).version(boundarySource.version()).importedRecords(imported)
					.sizeBytes(sizeBytes).downloadedAt(downloadedAt).importedAt(LocalDateTime.now(clock)).build());

			return status();
		} catch (RuntimeException e) {
			log.error("Geographic dataset download/import failed", e);

			metadataStore.read().ifPresentOrElse(existing -> {
				existing.setLastError(e.getMessage());

				metadataStore.write(existing);
			}, () -> metadataStore.write(BoundaryMetadata.builder().provider(boundarySource.providerLabel())
					.license(boundarySource.license()).lastError(e.getMessage()).build()));

			throw e;
		}
	}

	@Override
	@Transactional
	public void remove() {
		repository.deleteAllRows();

		geometryCache.invalidate();

		deleteRecursively(workspaceManager.geodata().resolve(DOWNLOADS));

		metadataStore.delete();

		log.info("Geographic dataset removed");
	}

	/**
	 * The main dataset dissolves some dependent territories into their sovereign
	 * state (e.g. Aruba inside the Netherlands polygon), making photos taken there
	 * resolve to the wrong country. After the main import, every ISO country left
	 * without a polygon of its own is fetched individually from the source and
	 * imported additively - fully data-driven, no hardcoded territory list.
	 * Failures here never undo the main dataset.
	 */
	private long completeMissingTerritories() {
		if (!appSettingService.booleanValue(SettingsConstants.BOUNDARY_AUTO_TERRITORIES, true)) {
			return 0;
		}

		Set<String> present = Set.copyOf(repository.findDistinctCountryCodes(AdminBoundaryKind.COUNTRY));

		List<String> missing = CountryCodes.alpha3ToAlpha2().entrySet().stream()
				.filter(entry -> !present.contains(entry.getValue())).map(Map.Entry::getKey).sorted().toList();

		if (missing.isEmpty()) {
			return 0;
		}

		List<LeveledBoundaryFile> files = boundarySource.fetchMissingCountries(missing, workspaceManager.geodata());

		if (files.isEmpty()) {
			return 0;
		}

		long imported = importer.importExtra(files, boundarySource.sourceTag(), boundarySource.version());

		log.info("Imported {} supplemental territory boundaries ({} ISO codes had no polygon)", imported,
				missing.size());

		return imported;
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