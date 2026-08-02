package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.CatalogRestored;

/**
 * The cache maps coordinates to the names a boundary dataset gave them, and a
 * restore brings another installation's dataset. The import already clears it
 * for the same reason; the restore had been leaving it behind.
 */
class LocationCacheRefreshTest {

	@Test
	void dropsResolutionsMadeAgainstTheReplacedBoundaries() {
		MediaLocationService mediaLocationService = mock(MediaLocationService.class);

		new LocationCacheRefresh(mediaLocationService).onCatalogRestored(new CatalogRestored("nimbus-catalog.zip"));

		verify(mediaLocationService).clearCache();
	}
}