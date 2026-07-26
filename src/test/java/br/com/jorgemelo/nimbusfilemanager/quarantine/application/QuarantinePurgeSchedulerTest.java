package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class QuarantinePurgeSchedulerTest {

	private final QuarantinePurgeService purgeService = mock(QuarantinePurgeService.class);
	private final QuarantinePurgeScheduler scheduler = new QuarantinePurgeScheduler(purgeService);

	@Test
	void runsPurgeWithConfiguredRetention() {
		when(purgeService.retentionDays()).thenReturn(30);

		scheduler.runOnce();

		verify(purgeService).purgeOlderThan(30);
	}

	@Test
	void skipsPurgeWhenRetentionIsDisabled() {
		when(purgeService.retentionDays()).thenReturn(0);

		scheduler.runOnce();

		verify(purgeService, never()).purgeOlderThan(anyInt());
	}

}