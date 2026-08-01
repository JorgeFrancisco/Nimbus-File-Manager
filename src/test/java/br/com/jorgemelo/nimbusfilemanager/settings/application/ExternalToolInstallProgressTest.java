package br.com.jorgemelo.nimbusfilemanager.settings.application;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.ToolInstallSnapshot;
import br.com.jorgemelo.nimbusfilemanager.settings.domain.enums.ToolInstallPhase;

class ExternalToolInstallProgressTest {

	private final ExternalToolInstallProgress progress = new ExternalToolInstallProgress();

	@Test
	void reportsThePercentageOfTheDownloadInCourse() {
		progress.startDownload(1_000);
		progress.addDownloadedBytes(250);

		ToolInstallSnapshot snapshot = progress.snapshot();

		Assertions.assertThat(snapshot.downloading()).isTrue();
		Assertions.assertThat(snapshot.percent()).isEqualTo(25D);
		Assertions.assertThat(snapshot.bytesDone()).isEqualTo(250);
	}

	@Test
	void movesFromDownloadToExtractionRestartingTheCount() {
		progress.startDownload(1_000);
		progress.addDownloadedBytes(1_000);

		progress.startExtraction(400);
		progress.addExtractedBytes(100);

		ToolInstallSnapshot snapshot = progress.snapshot();

		Assertions.assertThat(snapshot.extracting()).isTrue();
		Assertions.assertThat(snapshot.downloading()).isFalse();
		Assertions.assertThat(snapshot.bytesDone()).isEqualTo(100);
		Assertions.assertThat(snapshot.percent()).isEqualTo(25D);
	}

	/**
	 * A server that announces no length must not turn into a bogus percentage; the
	 * screen falls back to an indeterminate bar.
	 */
	@Test
	void reportsNoPercentageWhenTheTotalIsUnknown() {
		progress.startDownload(-1);
		progress.addDownloadedBytes(5_000);

		ToolInstallSnapshot snapshot = progress.snapshot();

		Assertions.assertThat(snapshot.bytesTotal()).isNegative();
		Assertions.assertThat(snapshot.percent()).isNegative();
		Assertions.assertThat(snapshot.etaSeconds()).isNegative();
	}

	/** The archive may carry entries with no declared size. */
	@Test
	void reportsNoPercentageWhenTheExtractionSizeIsUnknown() {
		progress.startExtraction(0);
		progress.addExtractedBytes(120);

		ToolInstallSnapshot snapshot = progress.snapshot();

		Assertions.assertThat(snapshot.extracting()).isTrue();
		Assertions.assertThat(snapshot.bytesTotal()).isNegative();
		Assertions.assertThat(snapshot.percent()).isNegative();
	}

	@Test
	void reportsNothingRunningAfterAReset() {
		progress.startDownload(1_000);
		progress.addDownloadedBytes(500);

		progress.reset();

		ToolInstallSnapshot snapshot = progress.snapshot();

		Assertions.assertThat(snapshot.phase()).isEqualTo(ToolInstallPhase.IDLE);
		Assertions.assertThat(snapshot.downloading()).isFalse();
		Assertions.assertThat(snapshot.bytesDone()).isZero();
	}
}