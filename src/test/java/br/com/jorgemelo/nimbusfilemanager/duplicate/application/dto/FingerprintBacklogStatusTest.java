package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class FingerprintBacklogStatusTest {

	@Test
	void totalBlockingAndPercentWhilePending() {
		FingerprintBacklogStatus status = new FingerprintBacklogStatus(5, 3, 2);

		Assertions.assertThat(status.total()).isEqualTo(10);
		Assertions.assertThat(status.blocking()).isTrue();
		Assertions.assertThat(status.percent()).isEqualTo(50);
	}

	@Test
	void unblocksAtHundredPercentEvenWithFailures() {
		FingerprintBacklogStatus status = new FingerprintBacklogStatus(0, 8, 2);

		Assertions.assertThat(status.blocking()).isFalse();
		Assertions.assertThat(status.percent()).isEqualTo(100);
	}

	@Test
	void emptyLibraryIsComplete() {
		FingerprintBacklogStatus status = new FingerprintBacklogStatus(0, 0, 0);

		Assertions.assertThat(status.blocking()).isFalse();
		Assertions.assertThat(status.percent()).isEqualTo(100);
	}
}