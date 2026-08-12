package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.GeoDatasetProgress;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LeveledBoundaryFile;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.AdminBoundaryKind;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.GeoAdminBoundaryRepository;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import lombok.extern.slf4j.Slf4j;

/**
 * The seventh stage of a dataset update, whole: whether the territories the main
 * dataset dissolves need completing, and the completing of them.
 *
 * <p>
 * The main dataset folds some dependent territories into their sovereign state -
 * Aruba inside the Netherlands polygon - so a photo taken there resolves to the
 * wrong country. Every ISO country left without a polygon of its own is fetched
 * individually and imported additively. Fully data-driven: no hardcoded
 * territory list.
 *
 * <p>
 * Its own class rather than a private method of the orchestrator because the
 * decision carries what nothing else there needs - the setting that turns it
 * off, the catalogue of ISO codes, the query for which of them are present - and
 * because one stage of the sequence should report itself. The orchestrator keeps
 * the sequence; this keeps one step of it.
 *
 * <p>
 * Failures never undo the main dataset: the additive import runs in a
 * transaction of its own.
 */
@Slf4j
@Service
public class BoundaryTerritoryCompletion {

	private final AppSettingService appSettingService;
	private final GeoAdminBoundaryRepository repository;
	private final BoundarySource boundarySource;
	private final GeoJsonBoundaryImporter importer;
	private final GeoDatasetProgress progress;

	public BoundaryTerritoryCompletion(AppSettingService appSettingService, GeoAdminBoundaryRepository repository,
			BoundarySource boundarySource, GeoJsonBoundaryImporter importer, GeoDatasetProgress progress) {
		this.appSettingService = appSettingService;
		this.repository = repository;
		this.boundarySource = boundarySource;
		this.importer = importer;
		this.progress = progress;
	}

	/**
	 * Runs the stage and answers how many supplemental boundaries it imported.
	 *
	 * <p>
	 * Three ways to have nothing to do - turned off, nothing missing, the source
	 * offering no file for what is missing - and all three are ordinary. Each says
	 * so and the stage is counted, because a stage that quietly vanished would make
	 * the same pipeline read as eight steps on one run and nine on the next.
	 */
	public long complete(Path geodataFolder) {
		List<String> missing = enabled() ? missingCountryCodes() : List.of();

		if (missing.isEmpty()) {
			progress.noTerritoriesMissing();

			progress.stageFinished();

			return 0;
		}

		progress.completingTerritories();

		long imported = importMissing(missing, geodataFolder);

		progress.stageFinished();

		return imported;
	}

	private boolean enabled() {
		return appSettingService.booleanValue(SettingsConstants.BOUNDARY_AUTO_TERRITORIES, true);
	}

	private List<String> missingCountryCodes() {
		Set<String> present = Set.copyOf(repository.findDistinctCountryCodes(AdminBoundaryKind.COUNTRY));

		return CountryCodes.alpha3ToAlpha2().entrySet().stream().filter(entry -> !present.contains(entry.getValue()))
				.map(Map.Entry::getKey).sorted().toList();
	}

	private long importMissing(List<String> missing, Path geodataFolder) {
		List<LeveledBoundaryFile> files = boundarySource.fetchMissingCountries(missing, geodataFolder);

		if (files.isEmpty()) {
			return 0;
		}

		long imported = importer.importExtra(files, boundarySource.sourceTag(), boundarySource.version());

		log.info("Imported {} supplemental territory boundaries ({} ISO codes had no polygon)", imported,
				missing.size());

		return imported;
	}
}