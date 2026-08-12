package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.EtaEstimate;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.EtaState;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * The one estimator, against the behaviour it was chosen for.
 *
 * <p>
 * The choice was made from a measured run rather than from taste: 169
 * fingerprint chunks with nothing else competing, in which the environment's
 * periodic stall took 18% of the wall clock from two occurrences. That is what
 * ruled out every short window - a median of recent samples included, which
 * discards exactly the cost that recurs and so promises a finish that does not
 * arrive. What is asserted here is that behaviour, not the shape of the formula.
 */
class EtaEstimatorTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, Month.AUGUST, 17, 10, 0);

	private final Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

	private final EtaEstimator estimator = Progress.estimator(clock);

	/** Steady progress divides out to the time the rest of it will take. */
	@Test
	void aSteadyRateAnswersWhatIsLeftOfTheWork() {
		// A quarter done in ten minutes leaves three quarters, so half an hour.
		Assertions.assertThat(estimate(1_000, 250, 10).remainingSeconds()).isEqualTo(1_800);
	}

	/**
	 * The measurement is the window's, not the run's - which is what lets a run
	 * recover from a bad patch instead of carrying it to the end.
	 *
	 * <p>
	 * A run that crawled for an hour and then sped up answers from the speed it
	 * has now. Under the cumulative average this class replaced, the hour of
	 * crawling stayed in the divisor and the estimate stayed wrong long after the
	 * cause had gone - which is the complaint the whole change exists to answer.
	 */
	@Test
	void aRunThatSpedUpIsNotJudgedByTheHourItCrawled() {
		Execution execution = row(10_000, 5_000);

		// The window opened five minutes ago and 2.500 arrived in it - a rate five
		// times what the run averaged over its first hour.
		execution.setRateWindowFromAt(NOW.minusMinutes(5));
		execution.setRateWindowFromDone(2_500);

		// 5.000 left at 2.500 per five minutes: ten minutes, not the hour the whole
		// run's average would have predicted.
		Assertions.assertThat(estimator.estimate(execution).remainingSeconds()).isEqualTo(600);
	}

	/**
	 * A stall inside the window counts, because it is going to happen again.
	 *
	 * <p>
	 * This is the finding that decided the algorithm. The stall measured was the
	 * database's periodic checkpoint: about 65 s, every five minutes, 18% of the
	 * wall clock. An estimator that treated it as an outlier - a median of recent
	 * samples, or any window too short to contain one - answered 13,6% to 16,8%
	 * short. The window here contains it, so the answer includes it.
	 */
	@Test
	void aRecurringStallIsCostAndNotAnOutlier() {
		Execution semParada = row(1_000, 100);

		semParada.setRateWindowFromAt(NOW.minusMinutes(5));
		semParada.setRateWindowFromDone(0);

		// The same five minutes, but a fifth of it was lost to a stall, so a fifth
		// fewer items came out of it. The estimate has to be longer, not equal.
		Execution comParada = row(1_000, 80);

		comParada.setRateWindowFromAt(NOW.minusMinutes(5));
		comParada.setRateWindowFromDone(0);

		Assertions.assertThat(estimator.estimate(comParada).remainingSeconds())
				.as("the stall is part of what the remaining work will cost")
				.isGreaterThan(estimator.estimate(semParada).remainingSeconds());
	}

	/** Too little measurement says so, rather than dividing by a moment. */
	@Test
	void tooShortAMeasurementIsStillCalculating() {
		Execution execution = row(1_000, 5);

		// Ten seconds is under a tenth of the window, which is the least this will
		// answer from - short enough to be one lucky burst.
		execution.setRateWindowFromAt(NOW.minusSeconds(10));
		execution.setRateWindowFromDone(0);

		Assertions.assertThat(estimator.estimate(execution).state()).isEqualTo(EtaState.CALCULATING);
	}

	/** And a window that opened but saw nothing move cannot divide either. */
	@Test
	void aWindowWithNoProgressInItIsStillCalculating() {
		Execution execution = row(1_000, 400);

		execution.setRateWindowFromAt(NOW.minusMinutes(10));
		execution.setRateWindowFromDone(400);

		Assertions.assertThat(estimator.estimate(execution).state()).isEqualTo(EtaState.CALCULATING);
	}

	/** A run with no window at all has not started measuring. */
	@Test
	void aRunWithNoWindowIsStillCalculating() {
		Assertions.assertThat(estimator.estimate(row(1_000, 400)).state()).isEqualTo(EtaState.CALCULATING);
	}

	@Test
	void workThatIsDoneHasNoTimeLeft() {
		Assertions.assertThat(estimate(1_000, 1_000, 10).remainingSeconds()).isZero();
	}

	/**
	 * A count past the total answers zero rather than a negative time. It happens:
	 * a backlog can grow a straggler after the total was captured, and a bar that
	 * reads "-3 min" is worse than one that reads "done".
	 */
	@Test
	void aCountPastTheTotalNeverAnswersANegativeTime() {
		Assertions.assertThat(estimate(1_000, 1_200, 10).remainingSeconds()).isZero();
	}

	@Test
	void withoutATotalThereIsNothingToEstimate() {
		Execution execution = row(0, 50);

		execution.setRateWindowFromAt(NOW.minusMinutes(10));
		execution.setRateWindowFromDone(0);

		Assertions.assertThat(estimator.estimate(execution).state()).isEqualTo(EtaState.NOT_APPLICABLE);
	}

	/**
	 * Nine stages whose costs run from a one-second check to a three-minute import
	 * are not nine of anything comparable, so no rate over them predicts the end.
	 * Saying so is the honest answer; "calculating…" would promise one forever.
	 */
	@Test
	void workWithoutAnHonestDenominatorSaysSoInsteadOfGuessing() {
		Execution geo = row(9, 6);

		geo.setExecutionType(ExecutionType.GEO_DATASET_UPDATE);
		geo.setRateWindowFromAt(NOW.minusMinutes(10));
		geo.setRateWindowFromDone(0);

		Assertions.assertThat(estimator.estimate(geo).state()).isEqualTo(EtaState.NOT_APPLICABLE);

		Execution similarity = row(1_000, 400);

		similarity.setExecutionType(ExecutionType.SIMILARITY_PHOTO);
		similarity.setRateWindowFromAt(NOW.minusMinutes(10));
		similarity.setRateWindowFromDone(0);

		Assertions.assertThat(estimator.estimate(similarity).state()).isEqualTo(EtaState.NOT_APPLICABLE);
	}

	/**
	 * The answer is rounded to what the measurement supports.
	 *
	 * <p>
	 * Measured against the real run, the best estimator predicted the next few
	 * minutes to within 20-25%. A figure of hours therefore lands on the hour, and
	 * one of minutes on the minute: announcing "4 h 56 min" over that error claims
	 * a precision the number does not have.
	 */
	@Test
	void theAnswerIsNotMorePreciseThanTheMeasurement() {
		Assertions.assertThat(estimate(113_084, 3_675, 10).remainingSeconds())
				.as("hours land on the hour").isEqualTo(18_000);

		Assertions.assertThat(estimate(1_000, 300, 10).remainingSeconds() % 300)
				.as("under an hour lands on five minutes").isZero();

		Assertions.assertThat(estimate(100, 40, 1).remainingSeconds() % 60)
				.as("under ten minutes lands on the minute").isZero();
	}

	/** A run nobody is measuring answers nothing at all. */
	@Test
	void thereIsNoEstimateForNoRun() {
		Assertions.assertThat(estimator.estimate(null)).isEqualTo(EtaEstimate.notApplicable());
	}

	private EtaEstimate estimate(int total, int done, int windowMinutes) {
		Execution execution = row(total, done);

		execution.setRateWindowFromAt(NOW.minusMinutes(windowMinutes));
		execution.setRateWindowFromDone(0);

		return estimator.estimate(execution);
	}

	private Execution row(int total, int done) {
		return Execution.builder().id(1L).executionType(ExecutionType.FINGERPRINT_PHOTO).startedAt(NOW.minusHours(1))
				.totalExpected(total).filesFound(done).filesAnalyzed(done).errors(0).build();
	}
}