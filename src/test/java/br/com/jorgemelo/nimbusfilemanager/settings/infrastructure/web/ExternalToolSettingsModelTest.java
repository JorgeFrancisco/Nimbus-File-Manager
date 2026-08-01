package br.com.jorgemelo.nimbusfilemanager.settings.infrastructure.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolInstallAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolInstaller;
import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.ExternalToolStatus;
import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.ToolInstallSnapshot;
import br.com.jorgemelo.nimbusfilemanager.settings.domain.enums.ToolInstallPhase;

/**
 * Every attribute the external-tools panel dereferences has to be published:
 * one missing name is a template expression that fails at render time, which no
 * unit test of the controller would catch.
 */
class ExternalToolSettingsModelTest {

	private static final ExternalToolStatus STATUS = new ExternalToolStatus(true, "tools/ffmpeg/bin/ffmpeg.exe", true,
			"tools/ffmpeg/bin/ffprobe.exe", "ffmpeg version 8.0", true, true, "C:/app/tools/ffmpeg/bin");

	private final ExternalToolInstaller installer = mock(ExternalToolInstaller.class);
	private final ExternalToolInstallAsyncRunner runner = mock(ExternalToolInstallAsyncRunner.class);

	private final ExternalToolSettingsModel model = new ExternalToolSettingsModel(installer, runner);

	@Test
	void publishesTheStatusAndTheProgressOfTheInstallation() {
		ToolInstallSnapshot snapshot = new ToolInstallSnapshot(ToolInstallPhase.DOWNLOADING, 10, 100, 10D, 5);

		when(installer.status()).thenReturn(STATUS);
		when(runner.isRunning()).thenReturn(true);
		when(runner.progress()).thenReturn(snapshot);

		Model attributes = new ExtendedModelMap();

		model.addTo(attributes, null);

		Assertions.assertThat(attributes.asMap()).containsEntry("toolStatus", STATUS)
				.containsEntry("toolInstallRunning", true).containsEntry("toolInstallProgress", snapshot);
	}

	/**
	 * The outcome of the previous run: the screen shows either of them, and both
	 * are absent while nothing has run yet.
	 */
	@Test
	void publishesTheOutcomeOfTheLastRun() {
		when(installer.status()).thenReturn(STATUS);
		when(runner.lastError()).thenReturn("Download failed with HTTP 404");
		when(runner.lastResult()).thenReturn(STATUS);

		Model attributes = new ExtendedModelMap();

		model.addTo(attributes, null);

		Assertions.assertThat(attributes.asMap()).containsEntry("toolInstallError", "Download failed with HTTP 404")
				.containsEntry("toolInstallResult", STATUS);
	}
}