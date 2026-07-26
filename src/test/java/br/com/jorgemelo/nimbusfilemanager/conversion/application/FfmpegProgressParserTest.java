package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class FfmpegProgressParserTest {

	private final FfmpegProgressParser parser = new FfmpegProgressParser();

	@Test
	void readsTheMicrosecondKey() {
		Assertions.assertThat(parser.elapsedMicroseconds("out_time_us=4000000")).hasValue(4_000_000);
		Assertions.assertThat(parser.elapsedMicroseconds("  out_time_us=250  ")).hasValue(250);
	}

	@Test
	void readsTheLegacyKeyThatAlsoCarriesMicroseconds() {
		Assertions.assertThat(parser.elapsedMicroseconds("out_time_ms=1500000")).hasValue(1_500_000);
	}

	@Test
	void ignoresEveryOtherProgressLine() {
		Assertions.assertThat(parser.elapsedMicroseconds("frame=120")).isEmpty();
		Assertions.assertThat(parser.elapsedMicroseconds("progress=continue")).isEmpty();
		Assertions.assertThat(parser.elapsedMicroseconds(null)).isEmpty();
	}

	@Test
	void ignoresTheNotAvailableAndNegativeValuesFfmpegEmitsBeforeTheFirstFrame() {
		Assertions.assertThat(parser.elapsedMicroseconds("out_time_us=N/A")).isEmpty();
		Assertions.assertThat(parser.elapsedMicroseconds("out_time_us=-1")).isEmpty();
	}

	@Test
	void turnsElapsedTimeIntoAPercentageOfTheSourceDuration() {
		Assertions.assertThat(parser.percent(30_000_000, 60.0)).isEqualTo(50);
		Assertions.assertThat(parser.percent(0, 60.0)).isZero();
	}

	@Test
	void clampsToOneHundredWhenTheEncoderRunsPastTheReportedDuration() {
		Assertions.assertThat(parser.percent(120_000_000, 60.0)).isEqualTo(100);
	}

	@Test
	void reportsZeroRatherThanGuessingWhenTheDurationIsUnknown() {
		Assertions.assertThat(parser.percent(30_000_000, null)).isZero();
		Assertions.assertThat(parser.percent(30_000_000, 0.0)).isZero();
	}
}