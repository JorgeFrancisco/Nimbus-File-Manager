package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;

class QuarantinePurgeSchedulerTest {

	private final AppSettingService appSettingService = mock(AppSettingService.class);
	private final QuarantinePurgeService purgeService = mock(QuarantinePurgeService.class);
	private final QuarantinePurgeScheduler scheduler = new QuarantinePurgeScheduler(appSettingService, purgeService);

	@Test
	void runsPurgeWithConfiguredRetention() {
		when(appSettingService.intValue(eq(SettingsConstants.TRASH_RETENTION_DAYS), anyInt())).thenReturn(30);

		scheduler.runOnce();

		verify(purgeService).purgeOlderThan(30);
	}

	@Test
	void skipsPurgeWhenRetentionIsDisabled() {
		when(appSettingService.intValue(eq(SettingsConstants.TRASH_RETENTION_DAYS), anyInt())).thenReturn(0);

		scheduler.runOnce();

		verify(purgeService, never()).purgeOlderThan(anyInt());
	}

	@Test
	void purgeIsFailSafeWhenTheRetentionSettingIsBlankOrInvalid() {
		// A blank/invalid retention resolves to the fallback inside AppSettingService,
		// so the scheduler must pass a non-positive fallback: an unreadable window
		// disables the destructive purge instead of silently defaulting to 90 days.
		when(appSettingService.intValue(eq(SettingsConstants.TRASH_RETENTION_DAYS), anyInt()))
				.thenAnswer(invocation -> invocation.getArgument(1));

		scheduler.runOnce();

		verify(purgeService, never()).purgeOlderThan(anyInt());
	}
}