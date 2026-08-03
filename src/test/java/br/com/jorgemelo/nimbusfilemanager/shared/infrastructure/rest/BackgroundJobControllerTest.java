package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.BackgroundJobActivity;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintActivityService;

/**
 * The banner polls this even when nothing is running, because the backlogs
 * start on their own: null is the answer that makes the banner disappear.
 */
class BackgroundJobControllerTest {

	private final FingerprintActivityService fingerprintActivityService = mock(FingerprintActivityService.class);
	private final BackgroundJobController controller = new BackgroundJobController(fingerprintActivityService);

	@Test
	void answersWithTheRunningJob() {
		BackgroundJobActivity job = new BackgroundJobActivity("Impressões digitais", "/app/duplicates", 10, 100, 10,
				60);

		when(fingerprintActivityService.current()).thenReturn(Optional.of(job));

		Assertions.assertThat(controller.current()).isSameAs(job);
	}

	@Test
	void answersWithNothingWhenNoJobIsRunning() {
		when(fingerprintActivityService.current()).thenReturn(Optional.empty());

		Assertions.assertThat(controller.current()).isNull();
	}
}