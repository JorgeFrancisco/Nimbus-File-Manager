package br.com.jorgemelo.nimbusfilemanager.settings.application;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.ExternalToolStatus;
import br.com.jorgemelo.nimbusfilemanager.settings.domain.enums.ToolInstallPhase;

class ExternalToolInstallAsyncRunnerTest {

	private static final ExternalToolStatus INSTALLED = new ExternalToolStatus(true, "tools/bin/ffmpeg.exe", true,
			"tools/bin/ffprobe.exe", "ffmpeg version 8.0", true, true, "C:/app/tools/bin");

	private final ExternalToolInstaller installer = mock(ExternalToolInstaller.class);
	private final ExternalToolInstallProgress progress = new ExternalToolInstallProgress();
	private final ExternalToolInstallAsyncRunner runner = new ExternalToolInstallAsyncRunner(installer, progress);

	@Test
	void keepsTheResultOfAFinishedInstallation() {
		when(installer.install()).thenReturn(INSTALLED);

		Assertions.assertThat(runner.start()).isTrue();

		runner.install();

		Assertions.assertThat(runner.isRunning()).isFalse();
		Assertions.assertThat(runner.lastResult()).isEqualTo(INSTALLED);
		Assertions.assertThat(runner.lastError()).isNull();
	}

	/**
	 * A second click while the first download is in flight must not start another
	 * one writing over the same folder.
	 */
	@Test
	void refusesToStartASecondInstallationWhileOneRuns() {
		Assertions.assertThat(runner.start()).isTrue();

		Assertions.assertThat(runner.start()).isFalse();
		Assertions.assertThat(runner.isRunning()).isTrue();
	}

	/**
	 * A failed download leaves the reason for the screen instead of a silent
	 * no-op, and does not keep the section blocked.
	 */
	@Test
	void keepsTheReasonWhenTheInstallationFails() {
		doThrow(new IllegalStateException("Download failed with HTTP 404")).when(installer).install();

		runner.start();
		runner.install();

		Assertions.assertThat(runner.isRunning()).isFalse();
		Assertions.assertThat(runner.lastError()).isEqualTo("Download failed with HTTP 404");
		Assertions.assertThat(runner.lastResult()).isNull();
	}

	/** Starting clears what the previous run left behind. */
	@Test
	void clearsTheProgressAndTheOutcomeOfThePreviousRun() {
		doThrow(new IllegalStateException("boom")).when(installer).install();

		runner.start();
		runner.install();

		progress.startDownload(1_000);
		progress.addDownloadedBytes(400);

		runner.start();

		Assertions.assertThat(runner.lastError()).isNull();
		Assertions.assertThat(runner.progress().phase()).isEqualTo(ToolInstallPhase.IDLE);
		Assertions.assertThat(runner.progress().percent()).isNegative();
	}
}