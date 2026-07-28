package br.com.jorgemelo.nimbusfilemanager.shared.util;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ProgressMathTest {

	@Test
	void percentShouldBeProportionalAndCapped() {
		Assertions.assertThat(ProgressMath.percent(0, 100)).isZero();
		Assertions.assertThat(ProgressMath.percent(25, 100)).isEqualTo(25);
		Assertions.assertThat(ProgressMath.percent(100, 100)).isEqualTo(100);
		Assertions.assertThat(ProgressMath.percent(150, 100)).isEqualTo(100);
	}

	/** Two decimals, so a long queue visibly moves between whole percents. */
	@Test
	void percentCarriesTwoDecimals() {
		Assertions.assertThat(ProgressMath.percent(1, 3)).isEqualTo(33.33);
		Assertions.assertThat(ProgressMath.percent(2, 3)).isEqualTo(66.67);
		Assertions.assertThat(ProgressMath.percent(6093, 6342)).isEqualTo(96.07);
	}

	/**
	 * The complaint every progress bar earns is reading finished while work goes
	 * on, and decimals make it easier to earn: 59,999 of 60,000 rounds up to
	 * 100.00. Only a done count that reached the total may say a hundred.
	 */
	@Test
	void percentNeverReachesAHundredBeforeTheWorkDoes() {
		Assertions.assertThat(ProgressMath.percent(59_999, 60_000)).isEqualTo(99.99).isLessThan(100);
		Assertions.assertThat(ProgressMath.percent(999_999, 1_000_000)).isLessThan(100);
		Assertions.assertThat(ProgressMath.percent(60_000, 60_000)).isEqualTo(100);
	}

	@Test
	void percentShouldBeUnknownWithoutTotal() {
		Assertions.assertThat(ProgressMath.percent(50, 0)).isEqualTo(-1);
		Assertions.assertThat(ProgressMath.percent(50, -1)).isEqualTo(-1);
	}

	@Test
	void etaShouldProjectRemainingTimeFromAverageRate() {
		// 10s elapsed for 25 of 100: 75 remaining at 2.5/s -> 30s.
		Assertions.assertThat(ProgressMath.etaSeconds(10_000, 25, 100)).isEqualTo(30);
	}

	@Test
	void etaShouldBeUnknownWhenRateIsMeaninglessOrNoisy() {
		Assertions.assertThat(ProgressMath.etaSeconds(10_000, 0, 100)).isEqualTo(-1);
		Assertions.assertThat(ProgressMath.etaSeconds(10_000, 50, 0)).isEqualTo(-1);
		Assertions.assertThat(ProgressMath.etaSeconds(10_000, 150, 100)).isEqualTo(-1);
		Assertions.assertThat(ProgressMath.etaSeconds(500, 25, 100)).isEqualTo(-1);
	}
}