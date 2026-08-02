package br.com.jorgemelo.nimbusfilemanager.settings.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.CatalogRestored;

/**
 * A restore rewrites the settings table without going through the service that
 * caches it, so the values this run had already read describe an installation
 * that no longer exists. The visible cost was the welcome wizard reopening on a
 * catalog that had just been restored with a folder configured.
 */
class SettingsCacheRefreshTest {

	@Test
	void dropsTheCachedSettingsWhenACatalogIsRestored() {
		AppSettingService appSettingService = mock(AppSettingService.class);

		new SettingsCacheRefresh(appSettingService).onCatalogRestored(new CatalogRestored("nimbus-catalog.zip"));

		verify(appSettingService).evictAll();
	}
}