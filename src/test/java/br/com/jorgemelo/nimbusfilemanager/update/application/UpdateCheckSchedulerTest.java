package br.com.jorgemelo.nimbusfilemanager.update.application;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.UpdateProperties;

/**
 * The timer that drives the check.
 *
 * <p>
 * It exists as a class rather than as a {@code @Scheduled} annotation because
 * this application has no {@code @EnableScheduling} - the annotation was there
 * first, fired never, and nothing said so: the check appeared to work because
 * the button on the settings screen calls the same method. It took leaving a
 * real installation running for fifteen minutes to notice.
 */
class UpdateCheckSchedulerTest {

	private final UpdateCheckService updateCheckService = mock(UpdateCheckService.class);

	@Test
	void asksTheServiceToCheck() {
		when(updateCheckService.check()).thenReturn(Optional.empty());

		scheduler(Duration.ofMinutes(15)).runOnce();

		verify(updateCheckService, atLeastOnce()).check();
	}

	/**
	 * {@code scheduleWithFixedDelay} stops forever the first time a task lets an
	 * exception escape, so a single unexpected failure would silently end every
	 * future check - the same class of silence this scheduler was written to fix.
	 */
	@Test
	void survivesAnUnexpectedFailureSoTheTimerKeepsRunning() {
		when(updateCheckService.check()).thenThrow(new IllegalStateException("boom"));

		UpdateCheckScheduler scheduler = scheduler(Duration.ofMinutes(15));

		Assertions.assertThatCode(scheduler::runOnce).doesNotThrowAnyException();
		Assertions.assertThatCode(scheduler::runOnce).doesNotThrowAnyException();

		verify(updateCheckService, times(2)).check();
	}

	/**
	 * A period of zero or less would make the executor reject the task outright,
	 * which would be the silent no-op all over again.
	 */
	@Test
	void refusesToScheduleAPeriodThatWouldNotRun() {
		Assertions.assertThatCode(() -> scheduler(Duration.ZERO)).doesNotThrowAnyException();
		Assertions.assertThatCode(() -> scheduler(Duration.ofSeconds(-30))).doesNotThrowAnyException();
	}

	@Test
	void stopsItsThreadOnShutdown() {
		UpdateCheckScheduler scheduler = scheduler(Duration.ofMinutes(15));

		Assertions.assertThatCode(scheduler::shutdown).doesNotThrowAnyException();
	}

	private UpdateCheckScheduler scheduler(Duration interval) {
		return new UpdateCheckScheduler(updateCheckService,
				new UpdateProperties(true, "https://example.invalid", interval));
	}
}