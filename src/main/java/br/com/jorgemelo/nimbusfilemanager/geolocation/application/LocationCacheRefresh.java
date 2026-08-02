package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.CatalogRestored;
import lombok.extern.slf4j.Slf4j;

/**
 * Drops resolutions made against boundaries the restore replaced.
 *
 * <p>
 * The cache maps coordinates to the names a dataset gave them, and the restore
 * brings the dataset of another installation - possibly a different version of
 * it. The dataset import already clears the cache for exactly this reason; a
 * restore changes the same tables and had been leaving it behind.
 */
@Slf4j
@Component
public class LocationCacheRefresh {

	private final MediaLocationService mediaLocationService;

	public LocationCacheRefresh(MediaLocationService mediaLocationService) {
		this.mediaLocationService = mediaLocationService;
	}

	/** Nothing here reads a setting, but the order is pinned all the same. */
	@Order(Ordered.LOWEST_PRECEDENCE)
	@EventListener
	public void onCatalogRestored(CatalogRestored event) {
		long removed = mediaLocationService.clearCache();

		log.info("Dropped {} cached locations after restoring {}", removed, event.name());
	}
}