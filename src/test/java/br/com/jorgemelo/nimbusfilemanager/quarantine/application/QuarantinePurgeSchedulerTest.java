package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class QuarantinePurgeSchedulerTest {

	private final QuarantineRetentionPolicy retentionPolicy = mock(QuarantineRetentionPolicy.class);
	private final QuarantineLauncherService launcher = mock(QuarantineLauncherService.class);
	private final QuarantinePurgeScheduler scheduler = new QuarantinePurgeScheduler(retentionPolicy, launcher);

	/**
	 * The pass no longer expunges anything itself: it asks, carrying the window, and
	 * a worker decides what is overdue when it actually runs.
	 */
	@Test
	void queuesAPurgeWithTheConfiguredRetention() {
		when(retentionPolicy.retentionDays()).thenReturn(30);

		scheduler.runOnce();

		verify(launcher).launchScheduledPurge(30);
	}

	@Test
	void skipsPurgeWhenRetentionIsDisabled() {
		when(retentionPolicy.retentionDays()).thenReturn(0);

		scheduler.runOnce();

		verify(launcher, never()).launchScheduledPurge(anyInt());
	}

	@Test
	void keepsTheDailyPassAliveWhenOneRunFails() {
		when(retentionPolicy.retentionDays()).thenThrow(new IllegalStateException("settings unreachable"));

		Assertions.assertThatCode(scheduler::runOnce).doesNotThrowAnyException();

		verify(launcher, never()).launchScheduledPurge(anyInt());
	}

	/**
	 * A failure caused by the shutdown itself is expected, so it must not surface
	 * as an ERROR with a stack trace in the log of a normal application stop.
	 */
	@Test
	void swallowsTheFailureOfAPassInterruptedByTheShutdown() {
		when(retentionPolicy.retentionDays()).thenThrow(new IllegalStateException("closing"));

		scheduler.shutdown();

		Assertions.assertThatCode(scheduler::runOnce).doesNotThrowAnyException();

		verify(launcher, never()).launchScheduledPurge(anyInt());
	}
}