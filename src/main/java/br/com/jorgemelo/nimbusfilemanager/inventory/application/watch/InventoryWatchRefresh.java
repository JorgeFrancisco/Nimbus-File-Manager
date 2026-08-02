package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch;

import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.CatalogRestored;
import lombok.extern.slf4j.Slf4j;

/**
 * Points the watcher at the folder the restored catalog names.
 *
 * <p>
 * The restore brings the settings of the installation the backup came from, so
 * the folder being watched is usually not the one that was watched a minute
 * ago - and on a fresh installation there was none at all. Ordered after the
 * settings cache is dropped, or this would reconfigure from the values the
 * restore just replaced.
 */
@Slf4j
@Component
@Order(100)
public class InventoryWatchRefresh {

	private final InventoryWatchService inventoryWatchService;

	public InventoryWatchRefresh(InventoryWatchService inventoryWatchService) {
		this.inventoryWatchService = inventoryWatchService;
	}

	@EventListener
	public void onCatalogRestored(CatalogRestored event) {
		inventoryWatchService.reconfigure();

		log.info("Inventory watcher reconfigured after restoring {}", event.name());
	}
}