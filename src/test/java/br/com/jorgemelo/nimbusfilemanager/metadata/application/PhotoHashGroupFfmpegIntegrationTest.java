package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static br.com.jorgemelo.nimbusfilemanager.shared.application.constants.ToolFolders.FFMPEG;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.PhotoPerceptualFingerprint;
import br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.FfmpegPhotoHashProcessRunner;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ExternalToolGate;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolPaths;
import br.com.jorgemelo.nimbusfilemanager.shared.application.ToolsLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.application.WorkspaceLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.ProcessingProperties;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionMetricsContext;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ProcessingMetrics;

/**
 * The grouped photo hash against the real ffmpeg binary, which is the only place
 * the claim it rests on can actually be checked: that one invocation over a list
 * of photos writes exactly one sample per photo, in list order.
 *
 * <p>
 * Everything else about the group is decided from that count, and a unit test
 * with a stubbed runner can only assume it. Runs where the bundled Windows
 * ffmpeg is present and skips elsewhere, like every other external-process glue;
 * this is the manual/local verification the {@code *ProcessRunner} coverage
 * exclusion refers to.
 */
@EnabledIf("ffmpegAvailable")
class PhotoHashGroupFfmpegIntegrationTest {

	private static final Path FFMPEG_EXECUTABLE = ToolsLocation.of(Path.of(WorkspaceLocation.resolve()), FFMPEG)
			.resolve("ffmpeg.exe");

	/** This test's own accumulator: nothing here is shared with another run. */
	private final ProcessingMetrics metrics = new ExecutionMetricsContext().processing();

	@TempDir
	Path tempDir;

	static boolean ffmpegAvailable() {
		return Files.exists(FFMPEG_EXECUTABLE);
	}

	/**
	 * One sample per photo, and each one the same sample that photo produces when
	 * it is decoded on its own. The second half is what makes this a test of the
	 * pairing and not just of the length: a group that came back in another order,
	 * or that dropped one and shifted the rest, has the right count and the wrong
	 * answers.
	 */
	@Test
	void aGroupAnswersForEveryPhotoWithTheSameSampleTheyGiveAlone() throws Exception {
		List<Path> photos = List.of(photo("a.jpg", "0.5"), photo("b.jpg", "2.5"), photo("c.jpg", "5.5"),
				photo("d.jpg", "8.5"));

		PhotoPerceptualHashService service = service();

		List<PhotoPerceptualFingerprint> grouped = service.computeGroup(photos, metrics);

		assertThat(grouped).hasSize(photos.size());

		// Four photos that differ, or a group that mixed them up would pass unnoticed.
		assertThat(grouped.stream().map(fingerprint -> Arrays.toString(fingerprint.luminance())).distinct())
				.hasSize(photos.size());

		for (int index = 0; index < photos.size(); index++) {
			PhotoPerceptualFingerprint alone = service.compute(photos.get(index), metrics);

			assertThat(grouped.get(index).luminance()).as("sample of %s", photos.get(index))
					.containsExactly(alone.luminance());
			assertThat(grouped.get(index).hash()).as("hash of %s", photos.get(index)).containsExactly(alone.hash());
		}
	}

	/**
	 * The list is read as text by the demuxer, so a quote or a space in a path is
	 * where it can go wrong - a line cut short names a file that is not there, and
	 * the group comes back one sample down. Real libraries have folders like this.
	 */
	@Test
	void aGroupFindsPhotosWhosePathsCarryQuotesAndSpaces() throws Exception {
		Path folder = Files.createDirectories(tempDir.resolve("Jorge's holiday photos"));

		List<Path> photos = List.of(photo(folder.resolve("first one.jpg"), "1.5"),
				photo(folder.resolve("second's.jpg"), "4.5"));

		List<PhotoPerceptualFingerprint> grouped = service().computeGroup(photos, metrics);

		assertThat(grouped).hasSize(photos.size());
	}

	/**
	 * The case a real library is made of, and the one that used to come back short.
	 * Fed to a single decoder, a photo whose size or pixel format differs from the
	 * one before it forces a reconfiguration that costs it its frame; the group
	 * then has one sample too few, and since position is all that names them, the
	 * whole group has to be thrown away and read again photo by photo.
	 */
	@Test
	void aGroupOfPhotosOfDifferentSizesAndFormatsAnswersForEveryOne() throws Exception {
		List<Path> photos = List.of(photo(tempDir.resolve("small.jpg"), "1.5", "320x240"),
				photo(tempDir.resolve("wide.jpg"), "2.5", "640x360"),
				photo(tempDir.resolve("tall.png"), "3.5", "240x480"),
				photo(tempDir.resolve("large.jpg"), "4.5", "800x600"));

		assertThat(service().computeGroup(photos, metrics)).hasSize(photos.size());
	}

	private PhotoPerceptualHashService service() {
		ExternalToolPaths externalToolPaths = mock(ExternalToolPaths.class);

		when(externalToolPaths.ffmpeg()).thenReturn(FFMPEG_EXECUTABLE.toString());

		WorkspaceManager workspaceManager = mock(WorkspaceManager.class);

		when(workspaceManager.temp()).thenReturn(tempDir);

		return new PhotoPerceptualHashService(externalToolPaths,
				new ExternalToolGate(new ProcessingProperties(2, 8, 2, 2, 2, 1)), new FfmpegPhotoHashProcessRunner(),
				workspaceManager);
	}

	private Path photo(String name, String second) throws Exception {
		return photo(tempDir.resolve(name), second);
	}

	private Path photo(Path destination, String second) throws Exception {
		return photo(destination, second, "320x240");
	}

	/** A frame of a moving test pattern, so each second is a different picture. */
	private Path photo(Path destination, String second, String size) throws Exception {
		List<String> command = new ArrayList<>(List.of(FFMPEG_EXECUTABLE.toString(), "-v", "error", "-y", "-f", "lavfi",
				"-i", "testsrc2=size=" + size + ":rate=30", "-ss", second, "-frames:v", "1", destination.toString()));

		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();

		String output = new String(process.getInputStream().readAllBytes());

		assertThat(process.waitFor(90, TimeUnit.SECONDS)).as("ffmpeg timed out").isTrue();
		assertThat(process.exitValue()).as("ffmpeg failed: %s", output).isZero();

		return destination;
	}
}