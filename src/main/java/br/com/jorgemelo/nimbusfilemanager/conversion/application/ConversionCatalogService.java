package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import java.nio.file.Path;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.InventoryPersistenceService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataOptions;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.ResolvedMediaDate;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.facade.MetadataFacade;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.model.MetadataResult;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import lombok.extern.slf4j.Slf4j;

/**
 * Brings a converted file into the catalog the moment it lands in the library,
 * instead of waiting for the watcher to notice it. It reuses the very same
 * extraction and persistence the inventory runs, so the new file gets the exact
 * same metadata, dates, hashes and location treatment as any other - there is
 * no second, conversion-specific idea of what a cataloged file looks like.
 */
@Slf4j
@Service
public class ConversionCatalogService {

	private final InventoryPersistenceService inventoryPersistenceService;
	private final MetadataFacade metadataFacade;
	private final AppSettingService appSettingService;

	public ConversionCatalogService(InventoryPersistenceService inventoryPersistenceService,
			MetadataFacade metadataFacade, AppSettingService appSettingService) {
		this.inventoryPersistenceService = inventoryPersistenceService;
		this.metadataFacade = metadataFacade;
		this.appSettingService = appSettingService;
	}

	/**
	 * @param originalDate the capture date already resolved for the file this one
	 * replaces, or {@code null} when there is none to inherit.
	 */
	public void catalog(Path file, ResolvedMediaDate originalDate) {
		// forceAnalysis, so a stale row left at this exact path (a file converted,
		// removed and converted again) is updated instead of quietly kept as a cache
		// hit with the old size, codec and hashes.
		MetadataOptions options = new MetadataOptions(calculateHashes(), true);

		MetadataResult metadata = keepingTheBestDate(metadataFacade.extract(file, options), originalDate);

		inventoryPersistenceService.save(file, inventoryRoot(file), metadata, options);
	}

	/**
	 * A converted file is written now, so its filesystem timestamps say "now". When
	 * the source had no embedded or name date either, the re-extraction can only
	 * answer with that - which would date decade-old footage as today and push it
	 * to the top of the timeline. The date the original had is more trustworthy
	 * than the conversion instant, so it is what the new row keeps.
	 */
	private MetadataResult keepingTheBestDate(MetadataResult metadata, ResolvedMediaDate originalDate) {
		if (originalDate == null || originalDate.captureDate() == null) {
			return metadata;
		}

		if (DateSource.trustOf(originalDate.dateSource(), originalDate.captureDate()) <= DateSource
				.trustOf(metadata.getDateSource(), metadata.getCaptureDate())) {
			return metadata;
		}

		log.info(
				"Converted file {} keeps the date of the file it replaces ({} from {}) instead of the {} it was"
						+ " written at",
				metadata.getFileName(), originalDate.captureDate(), originalDate.dateSource(),
				metadata.getDateSource());

		return metadata.toBuilder().captureDate(originalDate.captureDate()).dateSource(originalDate.dateSource())
				.build();
	}

	private boolean calculateHashes() {
		return appSettingService.booleanValue(SettingsConstants.WATCH_CALCULATE_HASHES, false);
	}

	/**
	 * The library root the file belongs to, matching what the watcher would record
	 * as its inventory path; the containing folder is the honest fallback while no
	 * library is configured.
	 */
	private Path inventoryRoot(Path file) {
		String watchFolder = appSettingService.stringValue(SettingsConstants.WATCH_FOLDER, "");

		if (watchFolder == null || watchFolder.isBlank()) {
			return file.getParent() == null ? file : file.getParent();
		}

		return Path.of(watchFolder).toAbsolutePath().normalize();
	}
}