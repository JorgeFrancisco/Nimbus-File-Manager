package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;

class CatalogFilePurgeSchedulerTest {

	private final AppSettingService appSettingService = mock(AppSettingService.class);
	private final CatalogFileRetentionService retentionService = mock(CatalogFileRetentionService.class);
	private final CatalogFilePurgeScheduler scheduler = new CatalogFilePurgeScheduler(appSettingService,
			retentionService);

	@Test
	void runsPurgeWithConfiguredRetention() {
		when(appSettingService.intValue(eq(SettingsConstants.CATALOG_MISSING_RETENTION_DAYS), anyInt())).thenReturn(30);

		scheduler.runOnce();

		verify(retentionService).purgeMissingOlderThan(30);
	}

	@Test
	void skipsPurgeWhenRetentionIsDisabled() {
		when(appSettingService.intValue(eq(SettingsConstants.CATALOG_MISSING_RETENTION_DAYS), anyInt())).thenReturn(0);

		scheduler.runOnce();

		verify(retentionService, never()).purgeMissingOlderThan(anyInt());
	}

	@Test
	void purgeIsFailSafeWhenTheRetentionSettingIsBlankOrInvalid() {
		// A blank/invalid retention resolves to the fallback inside AppSettingService,
		// so the scheduler must pass a non-positive fallback: an unreadable window
		// disables the destructive purge instead of silently using the product default.
		when(appSettingService.intValue(eq(SettingsConstants.CATALOG_MISSING_RETENTION_DAYS), anyInt()))
				.thenAnswer(invocation -> invocation.getArgument(1));

		scheduler.runOnce();

		verify(retentionService, never()).purgeMissingOlderThan(anyInt());
	}
}