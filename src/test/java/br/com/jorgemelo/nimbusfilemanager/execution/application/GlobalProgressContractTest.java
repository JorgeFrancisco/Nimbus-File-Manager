package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.util.ArrayList;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * The one arithmetic behind the first bar, held against the readings its
 * producers actually write.
 *
 * <p>
 * The bar is {@code filesFound / totalExpected}, computed once in
 * {@link ExecutionMapper} and drawn by a component that knows nothing about any
 * domain. That makes the contract a producer has to honour very small - the
 * counter means items concluded, the total means items expected - and makes
 * every way of breaking it look the same from here: a percentage that is full
 * before the work is, or one that goes backwards.
 *
 * <p>
 * Five producers were breaking it in three different ways. Four wrote the
 * <em>total</em> into the counter, so the bar was full from the first item;
 * one wrote a constant zero, so it never moved at all; and the conversion did
 * both in turn, which made it regress between one file and the next. These
 * sequences are the readings those producers now write, replayed through the
 * real formula.
 */
class GlobalProgressContractTest {

	private final ExecutionMapper mapper = new ExecutionMapper(null, null, Progress.reader(), Progress.estimator());

	/**
	 * A batch of ten videos: two were not eligible and are skipped up front, the
	 * rest are converted one by one. What the screen must never show is a full bar
	 * while a file is still encoding.
	 */
	@Test
	void aConversionBatchGrowsToAHundredOnlyWhenTheLastFileIsDone() {
		int total = 10;
		int skipped = 2;

		List<Double> readings = new ArrayList<>();

		for (int converted = 0; converted <= total - skipped; converted++) {
			readings.add(percent(converted + skipped, total));
		}

		assertClimbsToExactlyAHundred(readings);
	}

	/**
	 * The regression the two disagreeing call sites produced, stated as the
	 * property that forbids it: nothing a producer writes may be lower than what it
	 * wrote before.
	 */
	@Test
	void aConversionNeverReportsLessThanItAlreadyReported() {
		List<Double> readings = List.of(percent(2, 10), percent(3, 10), percent(4, 10), percent(5, 10));

		assertNeverGoesBackwards(readings);
	}

	@Test
	void aDeletionBatchGrowsOneItemAtATime() {
		int total = 4;

		List<Double> readings = new ArrayList<>();

		for (int processed = 0; processed <= total; processed++) {
			readings.add(percent(processed, total));
		}

		Assertions.assertThat(readings).startsWith(0.0);

		assertClimbsToExactlyAHundred(readings);
	}

	/**
	 * The backlog is the denominator and the work done is the numerator. Written
	 * the other way round - the backlog in both - every fingerprint run was full
	 * before it hashed anything.
	 */
	@Test
	void aFingerprintBacklogIsAFractionOfWhatItStartedWith() {
		int pending = 5;

		Assertions.assertThat(percent(0, pending)).isZero();
		Assertions.assertThat(percent(pending, pending)).isEqualTo(100.0);

		assertClimbsToExactlyAHundred(List.of(percent(0, pending), percent(1, pending), percent(3, pending),
				percent(pending, pending)));
	}

	/** A rebuild that resolved half its candidates has to say so, not say nothing. */
	@Test
	void aLocationRebuildMovesWhileItResolves() {
		Assertions.assertThat(percent(0, 200)).isZero();
		Assertions.assertThat(percent(100, 200)).isEqualTo(50.0);
		Assertions.assertThat(percent(200, 200)).isEqualTo(100.0);
	}

	/**
	 * Similarity publishes no total, and that is a deliberate absence rather than a
	 * broken bar: without a denominator there is no honest fraction, so the banner
	 * says "running" instead of drawing one. Inventing a total to make every type
	 * look alike would be inventing progress.
	 */
	@Test
	void aRunWithNoTotalHasNoBarAtAll() {
		Assertions.assertThat(mapper.percentComplete(running(7, null))).isNull();
		Assertions.assertThat(mapper.percentComplete(running(7, 0))).isNull();
	}

	/** No producer can push the bar past full, whatever it reports. */
	@Test
	void theBarIsNeverMoreThanFull() {
		Assertions.assertThat(percent(11, 10)).isEqualTo(100.0);
	}

	private void assertClimbsToExactlyAHundred(List<Double> readings) {
		assertNeverGoesBackwards(readings);

		Assertions.assertThat(readings.subList(0, readings.size() - 1))
				.allSatisfy(reading -> Assertions.assertThat(reading).isLessThan(100.0));

		Assertions.assertThat(readings.getLast()).isEqualTo(100.0);
	}

	private void assertNeverGoesBackwards(List<Double> readings) {
		for (int index = 1; index < readings.size(); index++) {
			Assertions.assertThat(readings.get(index)).as("reading %d went backwards", index)
					.isGreaterThanOrEqualTo(readings.get(index - 1));
		}
	}

	private Double percent(int found, Integer total) {
		return mapper.percentComplete(running(found, total));
	}

	private Execution running(int found, Integer total) {
		Execution execution = new Execution();

		execution.setStatus(ExecutionStatus.RUNNING);
		execution.setFilesFound(found);
		execution.setTotalExpected(total);

		return execution;
	}
}