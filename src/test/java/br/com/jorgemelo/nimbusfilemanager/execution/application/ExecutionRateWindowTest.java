package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * The window the estimate is measured over, as it rolls forward on the row.
 *
 * <p>
 * <b>Why two marks and not one.</b> A single anchor moved forward would reset
 * the measured span to zero every time it moved, so the estimate would drop back
 * to "calculating" once per window, forever. With an older mark and a younger
 * one, the younger becomes the older when it ages out and the span measured
 * never falls below a window.
 */
class ExecutionRateWindowTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, Month.AUGUST, 17, 10, 0);

	/** The shipped window, which is what production measures over. */
	private static final Duration WINDOW = Duration.ofMillis(Progress.properties().windowMillisOrDefault());

	@Test
	void theFirstReportOpensTheWindowWhereTheRunIsNow() {
		Execution execution = row(120);

		window(NOW).advance(execution);

		Assertions.assertThat(execution.getRateWindowFromAt()).isEqualTo(NOW);
		Assertions.assertThat(execution.getRateWindowFromDone()).isEqualTo(120);
		Assertions.assertThat(execution.getRateWindowMarkAt()).isEqualTo(NOW);
	}

	/**
	 * Reports inside the window leave both marks alone: what is being measured is
	 * the span since the older one, and moving it would shorten the measurement
	 * every few seconds.
	 */
	@Test
	void reportsInsideTheWindowDoNotMoveTheMarks() {
		Execution execution = row(0);

		window(NOW).advance(execution);

		execution.setFilesAnalyzed(500);

		window(NOW.plusMinutes(1)).advance(execution);

		Assertions.assertThat(execution.getRateWindowFromAt()).as("still measuring from where it opened")
				.isEqualTo(NOW);
		Assertions.assertThat(execution.getRateWindowFromDone()).isZero();
	}

	/**
	 * Once the younger mark is older than a window it becomes the older one, and a
	 * new younger mark opens - so the span drops from two windows to one rather
	 * than to nothing.
	 */
	@Test
	void anAgedMarkBecomesTheOneBeingMeasuredFrom() {
		Execution execution = row(0);

		window(NOW).advance(execution);

		LocalDateTime rolled = NOW.plus(WINDOW).plusSeconds(1);

		execution.setFilesAnalyzed(1_000);

		window(rolled).advance(execution);

		Assertions.assertThat(execution.getRateWindowFromAt()).as("the younger mark was promoted").isEqualTo(NOW);
		Assertions.assertThat(execution.getRateWindowMarkAt()).isEqualTo(rolled);
		Assertions.assertThat(execution.getRateWindowMarkDone()).isEqualTo(1_000);
	}

	/**
	 * A reclaim throws the measurement away, because the attempt that produced it
	 * is over: a new attempt does the work again, and carrying its predecessor's
	 * rate forward would describe work that is being repeated.
	 */
	@Test
	void clearingForgetsTheMeasurementEntirely() {
		Execution execution = row(400);

		window(NOW).advance(execution);

		window(NOW).clear(execution);

		Assertions.assertThat(execution.getRateWindowFromAt()).isNull();
		Assertions.assertThat(execution.getRateWindowFromDone()).isNull();
		Assertions.assertThat(execution.getRateWindowMarkAt()).isNull();
		Assertions.assertThat(execution.getRateWindowMarkDone()).isNull();
	}

	/** The count recorded is the workload's own, not whichever counter is handy. */
	@Test
	void theMarkRecordsWhatThisWorkloadCallsDone() {
		Execution execution = row(0);

		execution.setExecutionType(ExecutionType.INVENTORY);
		execution.setFilesFound(900);
		execution.setFilesAnalyzed(100);
		execution.setCacheHits(50);
		execution.setErrors(10);

		window(NOW).advance(execution);

		Assertions.assertThat(execution.getRateWindowFromDone()).as("analysed, cached and failed - not discovered")
				.isEqualTo(160);
	}

	/**
	 * The window costs no write of its own, which is the whole reason it can live
	 * on the row.
	 *
	 * <p>
	 * A fingerprint pass over a hundred thousand files commits every twenty-five of
	 * them, so it issues some four and a half thousand progress updates. Had the
	 * marks needed a statement of their own, that would have been four and a half
	 * thousand extra round trips and as much write-ahead log - on a workload where
	 * the log is already the visible bottleneck. They travel in the UPDATE that was
	 * happening anyway, and this proves the mechanism never asks for one: the marks
	 * only move when real work advanced, so a screen polling every second cannot
	 * cause a write.
	 */
	@Test
	void thePollingScreenCannotCauseAWrite() {
		Execution execution = row(0);

		window(NOW).advance(execution);

		LocalDateTime opened = execution.getRateWindowFromAt();
		Integer openedAt = execution.getRateWindowFromDone();

		// A hundred reads of the same row, seconds apart, with nothing having moved.
		for (int second = 1; second <= 100; second++) {
			Progress.estimator(clockAt(NOW.plusSeconds(second))).estimate(execution);
		}

		Assertions.assertThat(execution.getRateWindowFromAt()).as("reading never moves a mark").isEqualTo(opened);
		Assertions.assertThat(execution.getRateWindowFromDone()).isEqualTo(openedAt);
	}

	private ExecutionRateWindow window(LocalDateTime now) {
		return Progress.window(clockAt(now));
	}

	private Clock clockAt(LocalDateTime now) {
		return Clock.fixed(now.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
	}

	private Execution row(int done) {
		return Execution.builder().id(1L).executionType(ExecutionType.FINGERPRINT_PHOTO).totalExpected(10_000)
				.filesFound(done).filesAnalyzed(done).errors(0).build();
	}
}