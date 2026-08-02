package br.com.jorgemelo.nimbusfilemanager.inventory.application.watch;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.CatalogRestored;

/**
 * The restored settings usually name a different folder - or the first one, on
 * an installation that had none - so the watcher has to be pointed at it
 * instead of going on watching what the previous configuration said.
 */
class InventoryWatchRefreshTest {

	@Test
	void pointsTheWatcherAtTheRestoredFolder() {
		InventoryWatchService inventoryWatchService = mock(InventoryWatchService.class);

		new InventoryWatchRefresh(inventoryWatchService).onCatalogRestored(new CatalogRestored("nimbus-catalog.zip"));

		verify(inventoryWatchService).reconfigure();
	}
}