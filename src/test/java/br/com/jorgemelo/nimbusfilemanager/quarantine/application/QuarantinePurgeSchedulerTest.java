package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.assertj.core.api.Assertions;
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

	@Test
	void keepsTheDailyPassAliveWhenOneRunFails() {
		when(purgeService.retentionDays()).thenThrow(new IllegalStateException("settings unreachable"));

		Assertions.assertThatCode(scheduler::runOnce).doesNotThrowAnyException();

		verify(purgeService, never()).purgeOlderThan(anyInt());
	}

	/**
	 * A failure caused by the shutdown itself is expected, so it must not surface
	 * as an ERROR with a stack trace in the log of a normal application stop.
	 */
	@Test
	void swallowsTheFailureOfAPassInterruptedByTheShutdown() {
		when(purgeService.retentionDays()).thenThrow(new IllegalStateException("closing"));

		scheduler.shutdown();

		Assertions.assertThatCode(scheduler::runOnce).doesNotThrowAnyException();

		verify(purgeService, never()).purgeOlderThan(anyInt());
	}
}