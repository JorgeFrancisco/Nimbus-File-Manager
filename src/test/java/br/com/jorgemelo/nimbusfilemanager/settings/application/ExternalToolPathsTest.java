package br.com.jorgemelo.nimbusfilemanager.settings.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Resolution order of the external tools: the copy this application downloaded,
 * then the bare command name for PATH to answer. Both halves are worth pinning
 * - the downloaded copy winning is what keeps an installation on a known build
 * instead of whatever happens to be on the machine, and the bare command is
 * what lets a clone run on any platform without anyone editing a path,
 * something the hardcoded {@code .exe} default used to prevent.
 */
class ExternalToolPathsTest {

	/** The Windows case: the download landed, so it is what runs. */
	@Test
	void findsTheDownloadedWindowsBinary(@TempDir Path bundled) throws IOException {
		Files.createFile(bundled.resolve("ffmpeg.exe"));

		Assertions.assertThat(new ExternalToolPaths(bundled).ffmpeg())
				.isEqualTo(bundled.resolve("ffmpeg.exe").toString());
	}

	@Test
	void findsTheDownloadedBinaryWithoutAnExtension(@TempDir Path bundled) throws IOException {
		Files.createFile(bundled.resolve("ffprobe"));

		Assertions.assertThat(new ExternalToolPaths(bundled).ffprobe())
				.isEqualTo(bundled.resolve("ffprobe").toString());
	}

	/**
	 * A {@code .exe} left behind by a Windows download sitting next to the real
	 * binary must not win: on Linux it would be picked and then fail to execute.
	 */
	@Test
	void prefersTheExtensionlessBinaryWhenBothWereDownloaded(@TempDir Path bundled) throws IOException {
		Files.createFile(bundled.resolve("ffmpeg"));
		Files.createFile(bundled.resolve("ffmpeg.exe"));

		Assertions.assertThat(new ExternalToolPaths(bundled).ffmpeg())
				.isEqualTo(bundled.resolve("ffmpeg").toString());
	}

	/**
	 * Nothing downloaded yet - a first start, or a machine where the download
	 * keeps failing. The bare command lets the operating system resolve it through
	 * PATH, which is what keeps the features working meanwhile, and it is the
	 * fallback rather than the preference because a build already on the machine
	 * may be years older than the one this application fetches.
	 */
	@Test
	void fallsBackToTheBareCommandForThePathToResolve(@TempDir Path bundled) {
		ExternalToolPaths paths = new ExternalToolPaths(bundled);

		Assertions.assertThat(paths.ffmpeg()).isEqualTo("ffmpeg");
		Assertions.assertThat(paths.ffprobe()).isEqualTo("ffprobe");
	}

	/** The folder itself may not exist before the first download. */
	@Test
	void survivesAToolsFolderThatWasNeverCreated(@TempDir Path parent) {
		Assertions.assertThat(new ExternalToolPaths(parent.resolve("never-created")).ffmpeg()).isEqualTo("ffmpeg");
	}
}