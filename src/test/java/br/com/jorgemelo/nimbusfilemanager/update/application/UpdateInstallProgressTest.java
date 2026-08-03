package br.com.jorgemelo.nimbusfilemanager.update.application;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.update.application.dto.UpdateInstallSnapshot;
import br.com.jorgemelo.nimbusfilemanager.update.domain.enums.UpdatePhase;

/**
 * What the screen shows while an update installs. It exists because the first
 * real update spent about a minute downloading inside the request that asked
 * for it, so the page sat blank and came back only when it was already over -
 * which reads as a hang.
 */
class UpdateInstallProgressTest {

	private final UpdateInstallProgress progress = new UpdateInstallProgress();

	@Test
	void showsNothingBeforeAnythingStarts() {
		UpdateInstallSnapshot snapshot = progress.snapshot();

		Assertions.assertThat(snapshot.phase()).isEqualTo(UpdatePhase.IDLE);
		Assertions.assertThat(snapshot.running()).isFalse();
		Assertions.assertThat(snapshot.message()).isNull();
	}

	@Test
	void reportsHowMuchOfTheInstallerHasArrived() {
		progress.start();
		progress.startDownload(1000);
		progress.addDownloadedBytes(250);

		UpdateInstallSnapshot snapshot = progress.snapshot();

		Assertions.assertThat(snapshot.phase()).isEqualTo(UpdatePhase.DOWNLOADING);
		Assertions.assertThat(snapshot.running()).isTrue();
		Assertions.assertThat(snapshot.bytesDone()).isEqualTo(250);
		Assertions.assertThat(snapshot.bytesTotal()).isEqualTo(1000);
		Assertions.assertThat(snapshot.percent()).isEqualTo(25);
	}

	/**
	 * A server that announces no length is not a failure - the download still
	 * works, and the screen shows that it is happening without a percentage
	 * rather than showing a wrong one.
	 */
	@Test
	void reportsProgressWithoutAPercentageWhenTheSizeIsUnknown() {
		progress.start();
		progress.startDownload(-1);
		progress.addDownloadedBytes(4096);

		UpdateInstallSnapshot snapshot = progress.snapshot();

		Assertions.assertThat(snapshot.bytesDone()).isEqualTo(4096);
		Assertions.assertThat(snapshot.bytesTotal()).isEqualTo(-1);
		Assertions.assertThat(snapshot.percent()).isNegative();
	}

	@Test
	void movesThroughVerifyingAndStarting() {
		progress.start();
		progress.verifying();

		Assertions.assertThat(progress.snapshot().phase()).isEqualTo(UpdatePhase.VERIFYING);
		Assertions.assertThat(progress.snapshot().running()).isTrue();

		progress.starting();

		Assertions.assertThat(progress.snapshot().phase()).isEqualTo(UpdatePhase.STARTING);
	}

	/**
	 * The percentage belongs to the download alone. Leaving it on the screen
	 * during verification would show a bar that stopped moving for a reason
	 * nobody could see.
	 */
	@Test
	void stopsReportingAPercentageOnceTheDownloadIsOver() {
		progress.start();
		progress.startDownload(1000);
		progress.addDownloadedBytes(1000);
		progress.verifying();

		Assertions.assertThat(progress.snapshot().percent()).isNegative();
		Assertions.assertThat(progress.snapshot().etaSeconds()).isNegative();
	}

	/**
	 * The work outlives the request that started it, so the reason it stopped has
	 * to survive here - whoever asked may only come back to the screen later.
	 */
	@Test
	void keepsTheReasonItStoppedAndStopsBeingRunning() {
		progress.start();
		progress.failed("the bytes did not match");

		UpdateInstallSnapshot snapshot = progress.snapshot();

		Assertions.assertThat(snapshot.phase()).isEqualTo(UpdatePhase.FAILED);
		Assertions.assertThat(snapshot.running()).isFalse();
		Assertions.assertThat(snapshot.message()).isEqualTo("the bytes did not match");
	}

	@Test
	void clearsAPreviousFailureWhenAnotherInstallStarts() {
		progress.start();
		progress.failed("the bytes did not match");

		progress.start();

		Assertions.assertThat(progress.snapshot().message()).isNull();
		Assertions.assertThat(progress.snapshot().phase()).isEqualTo(UpdatePhase.DOWNLOADING);
		Assertions.assertThat(progress.snapshot().bytesDone()).isZero();
	}
}