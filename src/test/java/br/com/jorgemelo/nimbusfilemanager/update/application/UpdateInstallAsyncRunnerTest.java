package br.com.jorgemelo.nimbusfilemanager.update.application;

import static org.mockito.Mockito.when;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import br.com.jorgemelo.nimbusfilemanager.update.domain.enums.UpdateOutcome;
import br.com.jorgemelo.nimbusfilemanager.update.domain.enums.UpdatePhase;

/**
 * Runs the install behind the request that asked for it, and decides who may
 * ask again. Both halves matter: a second click while a download is running
 * would fetch the same file into the same folder, and a failure has to leave
 * the door open so it can be retried without restarting the application.
 */
class UpdateInstallAsyncRunnerTest {

	private final UpdateInstallService installService = Mockito.mock(UpdateInstallService.class);
	private final UpdateInstallProgress progress = new UpdateInstallProgress();

	private final UpdateInstallAsyncRunner runner = new UpdateInstallAsyncRunner(installService, progress);

	@Test
	void claimsTheInstallOnlyOnce() {
		Assertions.assertThat(runner.start()).isTrue();
		Assertions.assertThat(runner.start()).isFalse();
		Assertions.assertThat(runner.isRunning()).isTrue();
	}

	@Test
	void marksTheProgressAsStartedWhenItClaims() {
		runner.start();

		Assertions.assertThat(progress.snapshot().running()).isTrue();
		Assertions.assertThat(progress.snapshot().phase()).isEqualTo(UpdatePhase.DOWNLOADING);
	}

	/**
	 * The claim is kept once the installer is running: this run is ending, and a
	 * second attempt would only race the shutdown.
	 */
	@Test
	void keepsTheClaimOnceTheInstallerIsRunning() {
		when(installService.install()).thenReturn(UpdateOutcome.STARTED);

		runner.start();
		runner.install();

		Assertions.assertThat(runner.isRunning()).isTrue();
		Assertions.assertThat(runner.start()).isFalse();
		Assertions.assertThat(progress.snapshot().phase()).isNotEqualTo(UpdatePhase.FAILED);
	}

	/**
	 * A refusal is the opposite: the application stays up, so the person is still
	 * there to read the reason and try again.
	 */
	@Test
	void reportsTheReasonAndFreesTheClaimWhenItIsRefused() {
		when(installService.install()).thenReturn(UpdateOutcome.CHECKSUM_MISMATCH);

		runner.start();
		runner.install();

		Assertions.assertThat(progress.snapshot().phase()).isEqualTo(UpdatePhase.FAILED);
		Assertions.assertThat(progress.snapshot().message()).isNotBlank();
		Assertions.assertThat(runner.isRunning()).isFalse();
		Assertions.assertThat(runner.start()).isTrue();
	}

	@Test
	void reportsEveryRefusalWithAReasonOfItsOwn() {
		for (UpdateOutcome outcome : UpdateOutcome.values()) {
			if (outcome == UpdateOutcome.STARTED) {
				continue;
			}

			when(installService.install()).thenReturn(outcome);

			runner.start();
			runner.install();

			Assertions.assertThat(progress.snapshot().message()).as("reason for %s", outcome).isNotBlank();
		}
	}

	/**
	 * Nothing may escape the background thread: an exception that got out would
	 * leave the claim held forever, and the screen would keep showing a download
	 * that is not happening.
	 */
	@Test
	void survivesAnUnexpectedFailureAndFreesTheClaim() {
		when(installService.install()).thenThrow(new IllegalStateException("boom"));

		runner.start();
		runner.install();

		Assertions.assertThat(progress.snapshot().phase()).isEqualTo(UpdatePhase.FAILED);
		Assertions.assertThat(progress.snapshot().message()).isNotBlank();
		Assertions.assertThat(runner.isRunning()).isFalse();
	}
}