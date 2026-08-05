package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * Where the screen's "is it running, and for how much longer" comes from now.
 *
 * <p>
 * Both answers used to be fields of a runner, which worked only while the drain
 * happened in the process being asked. It does not, so both are read from the
 * row - and that is what makes them survive a restart of either side and agree
 * between two open tabs.
 */
class FingerprintRunReaderTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, Month.MAY, 1, 10, 0);

	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);
	private final Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

	private final FingerprintRunReader reader = new FingerprintRunReader(executionRepository, clock);

	@Test
	void aBacklogWithNoRunIsNotRunningAndHasNothingToEstimate() {
		when(executionRepository.findFirstByExecutionTypeAndStatusInOrderByStartedAtDesc(any(), any()))
				.thenReturn(Optional.empty());

		Assertions.assertThat(reader.isRunning(ExecutionType.FINGERPRINT_PHOTO)).isFalse();
		Assertions.assertThat(reader.etaSeconds(ExecutionType.FINGERPRINT_PHOTO)).isEqualTo(-1);
	}

	@Test
	void eachMediaIsAskedAboutOnItsOwn() {
		when(executionRepository.findFirstByExecutionTypeAndStatusInOrderByStartedAtDesc(
				eq(ExecutionType.FINGERPRINT_VIDEO), any())).thenReturn(Optional.of(run(NOW.minusMinutes(1), 100, 50)));
		when(executionRepository.findFirstByExecutionTypeAndStatusInOrderByStartedAtDesc(
				eq(ExecutionType.FINGERPRINT_PHOTO), any())).thenReturn(Optional.empty());

		Assertions.assertThat(reader.isRunning(ExecutionType.FINGERPRINT_VIDEO)).isTrue();
		Assertions.assertThat(reader.isRunning(ExecutionType.FINGERPRINT_PHOTO)).isFalse();
	}

	/**
	 * Half of a hundred items in a minute leaves about a minute: the estimate is
	 * elapsed time against the fraction reached, and both come from the row.
	 */
	@Test
	void theEstimateComesFromHowFarTheRowSaysItGot() {
		when(executionRepository.findFirstByExecutionTypeAndStatusInOrderByStartedAtDesc(any(), any()))
				.thenReturn(Optional.of(run(NOW.minusMinutes(1), 100, 50)));

		Assertions.assertThat(reader.etaSeconds(ExecutionType.FINGERPRINT_PHOTO)).isEqualTo(60);
	}

	/** Failures count as reached: they are items the run will not come back to. */
	@Test
	void whatFailedCountsTowardsTheEstimate() {
		Execution execution = run(NOW.minusMinutes(1), 100, 25);

		execution.setErrors(25);

		when(executionRepository.findFirstByExecutionTypeAndStatusInOrderByStartedAtDesc(any(), any()))
				.thenReturn(Optional.of(execution));

		Assertions.assertThat(reader.etaSeconds(ExecutionType.FINGERPRINT_PHOTO)).isEqualTo(60);
	}

	/**
	 * A run that has not started, or one whose counts are not there yet, has
	 * nothing to estimate from - and says so instead of dividing by a zero.
	 */
	@Test
	void aRunWithNothingToDivideByEstimatesNothing() {
		Execution notStarted = run(null, 100, 50);

		when(executionRepository.findFirstByExecutionTypeAndStatusInOrderByStartedAtDesc(any(), any()))
				.thenReturn(Optional.of(notStarted));

		Assertions.assertThat(reader.etaSeconds(ExecutionType.FINGERPRINT_PHOTO)).isEqualTo(-1);

		Execution noCounts = run(NOW.minusMinutes(1), null, null);

		noCounts.setErrors(null);

		when(executionRepository.findFirstByExecutionTypeAndStatusInOrderByStartedAtDesc(any(), any()))
				.thenReturn(Optional.of(noCounts));

		Assertions.assertThat(reader.etaSeconds(ExecutionType.FINGERPRINT_PHOTO)).isEqualTo(-1);
	}

	private Execution run(LocalDateTime startedAt, Integer filesFound, Integer filesAnalyzed) {
		return Execution.builder().id(1L).executionType(ExecutionType.FINGERPRINT_PHOTO).startedAt(startedAt)
				.filesFound(filesFound).filesAnalyzed(filesAnalyzed).errors(0).build();
	}
}