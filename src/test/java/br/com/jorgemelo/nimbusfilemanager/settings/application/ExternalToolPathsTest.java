package br.com.jorgemelo.nimbusfilemanager.settings.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Tools;

/**
 * Resolution order of the external tools: what the operator saved on the
 * settings screen, then the configured value, then discovery. Discovery is the
 * part worth pinning - it is what lets a clone run on any platform without
 * anyone editing a path, which the hardcoded {@code .exe} default prevented.
 */
class ExternalToolPathsTest {

	private final AppSettingService appSettingService = mock(AppSettingService.class);

	private ExternalToolPaths paths(Path bundled, Tools tools) {
		return new ExternalToolPaths(appSettingService, properties(tools), bundled);
	}

	private NimbusFileManagerProperties properties(Tools tools) {
		return new NimbusFileManagerProperties(null, tools, null, null, null, null);
	}

	/**
	 * Answers the caller's own fallback, mirroring an AppSettingService with
	 * nothing stored for the key.
	 */
	private void nothingStored() {
		when(appSettingService.stringValue(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
	}

	@Test
	void theSavedSettingWinsOverEverythingElse(@TempDir Path bundled) {
		when(appSettingService.stringValue(SettingsConstants.TOOL_FFMPEG, "C:/tools/ffmpeg.exe"))
				.thenReturn("D:/custom/ffmpeg.exe");

		Tools tools = new Tools("C:/tools/ffprobe.exe", "C:/tools/ffmpeg.exe", true);

		Assertions.assertThat(paths(bundled, tools).ffmpeg()).isEqualTo("D:/custom/ffmpeg.exe");
	}

	@Test
	void fallsBackToTheConfiguredPathWhenNothingWasSaved(@TempDir Path bundled) {
		nothingStored();

		Tools tools = new Tools("/usr/bin/ffprobe", "/usr/bin/ffmpeg", true);

		Assertions.assertThat(paths(bundled, tools).ffmpeg()).isEqualTo("/usr/bin/ffmpeg");
		Assertions.assertThat(paths(bundled, tools).ffprobe()).isEqualTo("/usr/bin/ffprobe");
	}

	/** The Windows case: nothing configured, packaged binary right there. */
	@Test
	void findsTheBundledWindowsBinary(@TempDir Path bundled) throws IOException {
		nothingStored();

		Files.createFile(bundled.resolve("ffmpeg.exe"));

		Assertions.assertThat(paths(bundled, new Tools("", "", true)).ffmpeg())
				.isEqualTo(bundled.resolve("ffmpeg.exe").toString());
	}

	@Test
	void findsTheBundledBinaryWithoutAnExtension(@TempDir Path bundled) throws IOException {
		nothingStored();

		Files.createFile(bundled.resolve("ffprobe"));

		Assertions.assertThat(paths(bundled, new Tools("", "", true)).ffprobe())
				.isEqualTo(bundled.resolve("ffprobe").toString());
	}

	/**
	 * A {@code .exe} left behind by a Windows download sitting next to the real
	 * binary must not win: on Linux it would be picked and then fail to execute.
	 */
	@Test
	void prefersTheExtensionlessBinaryWhenBothAreBundled(@TempDir Path bundled) throws IOException {
		nothingStored();

		Files.createFile(bundled.resolve("ffmpeg"));
		Files.createFile(bundled.resolve("ffmpeg.exe"));

		Assertions.assertThat(paths(bundled, new Tools("", "", true)).ffmpeg())
				.isEqualTo(bundled.resolve("ffmpeg").toString());
	}

	/**
	 * Nothing configured and nothing bundled - a clone on Linux or macOS. The bare
	 * command lets the operating system resolve it through PATH, which the old
	 * {@code ./tools/ffmpeg/bin/ffmpeg.exe} default made impossible.
	 */
	@Test
	void fallsBackToTheBareCommandForThePathToResolve(@TempDir Path bundled) {
		nothingStored();

		ExternalToolPaths paths = paths(bundled, new Tools("", "", true));

		Assertions.assertThat(paths.ffmpeg()).isEqualTo("ffmpeg");
		Assertions.assertThat(paths.ffprobe()).isEqualTo("ffprobe");
	}

	/**
	 * A configuration with no tools section at all still resolves, instead of
	 * failing on the missing record.
	 */
	@Test
	void survivesAConfigurationWithoutAToolsSection(@TempDir Path bundled) {
		nothingStored();

		Assertions.assertThat(paths(bundled, null).ffmpeg()).isEqualTo("ffmpeg");
	}
}