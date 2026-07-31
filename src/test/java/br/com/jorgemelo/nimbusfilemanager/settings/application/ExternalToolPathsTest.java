package br.com.jorgemelo.nimbusfilemanager.settings.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Tools;

/**
 * The tool paths are editable on the settings screen, so what the operator
 * saved there wins over the value shipped in the configuration file - which is
 * only the default offered while the setting was never touched.
 */
class ExternalToolPathsTest {

	private final AppSettingService appSettingService = mock(AppSettingService.class);

	@Test
	void theSavedSettingWinsOverTheConfiguredDefault() {
		when(appSettingService.stringValue(SettingsConstants.TOOL_FFMPEG, "C:/tools/ffmpeg.exe"))
				.thenReturn("D:/custom/ffmpeg.exe");
		when(appSettingService.stringValue(SettingsConstants.TOOL_FFPROBE, "C:/tools/ffprobe.exe"))
				.thenReturn("C:/tools/ffprobe.exe");

		ExternalToolPaths paths = new ExternalToolPaths(appSettingService, properties());

		Assertions.assertThat(paths.ffmpeg()).isEqualTo("D:/custom/ffmpeg.exe");
		Assertions.assertThat(paths.ffprobe()).isEqualTo("C:/tools/ffprobe.exe");
	}

	private NimbusFileManagerProperties properties() {
		return new NimbusFileManagerProperties(null, null,
				new Tools("C:/tools/ffprobe.exe", "C:/tools/ffmpeg.exe", "C:/tools/exiftool.exe"), null, null, null, null,
				null);
	}
}