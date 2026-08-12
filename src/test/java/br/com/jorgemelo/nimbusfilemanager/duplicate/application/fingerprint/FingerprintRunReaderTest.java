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

import br.com.jorgemelo.nimbusfilemanager.execution.application.Progress;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.EtaState;
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
 *
 * <p>
 * The arithmetic itself is no longer here: it belongs to the one estimator the
 * application has, and is proved against it. What is proved here is that this
 * reader asks about the right run and hands the answer through unchanged.
 */
class FingerprintRunReaderTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, Month.MAY, 1, 10, 0);

	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);
	private final Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

	private final FingerprintRunReader reader = new FingerprintRunReader(executionRepository,
			Progress.estimator(clock));

	@Test
	void aBacklogWithNoRunIsNotRunningAndHasNothingToEstimate() {
		when(executionRepository.findFirstByExecutionTypeAndStatusInOrderByStartedAtDesc(any(), any()))
				.thenReturn(Optional.empty());

		Assertions.assertThat(reader.isRunning(ExecutionType.FINGERPRINT_PHOTO)).isFalse();
		Assertions.assertThat(reader.eta(ExecutionType.FINGERPRINT_PHOTO).state())
				.isEqualTo(EtaState.NOT_APPLICABLE);
	}

	@Test
	void eachMediaIsAskedAboutOnItsOwn() {
		when(executionRepository.findFirstByExecutionTypeAndStatusInOrderByStartedAtDesc(
				eq(ExecutionType.FINGERPRINT_VIDEO), any())).thenReturn(Optional.of(run(100, 50)));
		when(executionRepository.findFirstByExecutionTypeAndStatusInOrderByStartedAtDesc(
				eq(ExecutionType.FINGERPRINT_PHOTO), any())).thenReturn(Optional.empty());

		Assertions.assertThat(reader.isRunning(ExecutionType.FINGERPRINT_VIDEO)).isTrue();
		Assertions.assertThat(reader.isRunning(ExecutionType.FINGERPRINT_PHOTO)).isFalse();
	}

	/**
	 * The shape a real drain writes, which is the one the estimate was blind to.
	 *
	 * <p>
	 * Progress is reported as {@code filesFound = filesAnalyzed = done}: the two
	 * counters carry the same running count, and the backlog lives in
	 * {@code totalExpected} alone. Dividing by {@code filesFound} divided by the
	 * numerator, so the remainder was always zero - a panel with a hundred
	 * thousand files still to hash said "less than a minute", and one failure was
	 * enough to push the numerator past the total and turn it into "calculating"
	 * for the rest of the run.
	 *
	 * <p>
	 * The numbers are a real run: ten minutes of measured window, 3.675 of 113.084
	 * hashed.
	 */
	@Test
	void theEstimateDividesByTheBacklogRatherThanByTheProgressCounter() {
		Execution execution = run(113_084, 3_675);

		when(executionRepository.findFirstByExecutionTypeAndStatusInOrderByStartedAtDesc(any(), any()))
				.thenReturn(Optional.of(execution));

		Assertions.assertThat(execution.getFilesFound()).as("what a drain actually reports")
				.isEqualTo(execution.getFilesAnalyzed()).isLessThan(execution.getTotalExpected());

		// 600 s bought 3.675 of them, so the remaining 109.409 are about five hours
		// away. The point is the order of magnitude: dividing by filesFound answered
		// zero here.
		Assertions.assertThat(reader.eta(ExecutionType.FINGERPRINT_PHOTO).remainingSeconds())
				.isEqualTo(18_000);
	}

	/** Failures count as reached: they are items the run will not come back to. */
	@Test
	void whatFailedCountsTowardsTheEstimate() {
		Execution execution = run(100, 25);

		execution.setErrors(25);

		when(executionRepository.findFirstByExecutionTypeAndStatusInOrderByStartedAtDesc(any(), any()))
				.thenReturn(Optional.of(execution));

		// Fifty of a hundred in the ten minutes measured, so about ten minutes left -
		// which counting only the analysed half would have doubled.
		Assertions.assertThat(reader.eta(ExecutionType.FINGERPRINT_PHOTO).remainingSeconds())
				.isEqualTo(600);
	}

	/**
	 * A run whose window has not opened yet has nothing to divide by, and says so
	 * rather than dividing by a zero.
	 */
	@Test
	void aRunWithNothingToDivideByIsStillCalculating() {
		Execution unmeasured = Execution.builder().id(1L).executionType(ExecutionType.FINGERPRINT_PHOTO)
				.startedAt(NOW.minusMinutes(1)).totalExpected(100).filesFound(10).filesAnalyzed(10).errors(0).build();

		when(executionRepository.findFirstByExecutionTypeAndStatusInOrderByStartedAtDesc(any(), any()))
				.thenReturn(Optional.of(unmeasured));

		Assertions.assertThat(reader.eta(ExecutionType.FINGERPRINT_PHOTO).state()).isEqualTo(EtaState.CALCULATING);
	}

	/**
	 * A row shaped the way a drain shapes it: the backlog it set out to drain in
	 * {@code totalExpected}, both progress counters carrying the same running
	 * count, and a measurement window that opened ten minutes ago with nothing
	 * done yet.
	 *
	 * <p>
	 * The previous fixture gave {@code filesFound} a total of its own, which no
	 * drain ever writes - and an estimate that divided by it passed here while
	 * being blind in production.
	 */
	private Execution run(Integer totalExpected, Integer done) {
		return Execution.builder().id(1L).executionType(ExecutionType.FINGERPRINT_PHOTO).startedAt(NOW.minusMinutes(10))
				.totalExpected(totalExpected).filesFound(done).filesAnalyzed(done).errors(0)
				.rateWindowFromAt(NOW.minusMinutes(10)).rateWindowFromDone(0).build();
	}
}