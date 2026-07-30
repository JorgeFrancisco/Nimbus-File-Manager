package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.assertj.core.api.Assertions;
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

	/**
	 * A destructive background pass must never take the application down: an
	 * unexpected failure is logged and the timer keeps its schedule.
	 */
	@Test
	void purgeSwallowsAnUnexpectedFailureSoTheTimerSurvives() {
		when(appSettingService.intValue(eq(SettingsConstants.CATALOG_MISSING_RETENTION_DAYS), anyInt())).thenReturn(30);
		doThrow(new IllegalStateException("catalog unreachable")).when(retentionService).purgeMissingOlderThan(30);

		Assertions.assertThatCode(scheduler::runOnce).doesNotThrowAnyException();
	}

	/**
	 * The same failure during shutdown is expected, not news: the pass is being
	 * interrupted on purpose, so it is not reported as an error.
	 */
	@Test
	void purgeStaysQuietWhenTheFailureComesFromShuttingDown() {
		when(appSettingService.intValue(eq(SettingsConstants.CATALOG_MISSING_RETENTION_DAYS), anyInt())).thenReturn(30);
		doThrow(new IllegalStateException("interrupted")).when(retentionService).purgeMissingOlderThan(30);

		scheduler.shutdown();

		Assertions.assertThatCode(scheduler::runOnce).doesNotThrowAnyException();
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