package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class LuminanceSsimServiceTest {

	private final LuminanceSsimService service = new LuminanceSsimService();

	@Test
	void identicalSamplesAreOneHundredPercent() {
		byte[] sample = filled(120);

		assertThat(service.similarityPercent(sample, sample.clone())).isEqualTo(100);
	}

	@Test
	void veryDifferentSamplesAreNotReportedAsIdentical() {
		assertThat(service.similarityPercent(filled(0), filled(255))).isLessThan(10);
	}

	@Test
	void smallLuminanceChangeRemainsHighlySimilar() {
		assertThat(service.similarityPercent(filled(120), filled(125))).isGreaterThanOrEqualTo(95);
	}

	/**
	 * The samples come from ffmpeg: comparing anything other than a 32x32 sample
	 * would answer a similarity nobody could trust.
	 */
	@Test
	void samplesOfTheWrongSizeAreRefused() {
		byte[] sample = filled(120);

		assertThatThrownBy(() -> service.similarityPercent(sample, new byte[3]))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.similarityPercent(null, sample)).isInstanceOf(IllegalArgumentException.class);
	}

	private byte[] filled(int value) {
		byte[] sample = new byte[1024];

		Arrays.fill(sample, (byte) value);

		return sample;
	}
}