package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import java.nio.file.Path;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.inventory.application.InventoryPersistenceService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataOptions;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.facade.MetadataFacade;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.model.MetadataResult;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;

/**
 * Brings a converted file into the catalog the moment it lands in the library,
 * instead of waiting for the watcher to notice it. It reuses the very same
 * extraction and persistence the inventory runs, so the new file gets the exact
 * same metadata, dates, hashes and location treatment as any other - there is
 * no second, conversion-specific idea of what a cataloged file looks like.
 */
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

	public void catalog(Path file) {
		// forceAnalysis, so a stale row left at this exact path (a file converted,
		// removed and converted again) is updated instead of quietly kept as a cache
		// hit with the old size, codec and hashes.
		MetadataOptions options = new MetadataOptions(calculateHashes(), true);

		MetadataResult metadata = metadataFacade.extract(file, options);

		inventoryPersistenceService.save(file, inventoryRoot(file), metadata, options);
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