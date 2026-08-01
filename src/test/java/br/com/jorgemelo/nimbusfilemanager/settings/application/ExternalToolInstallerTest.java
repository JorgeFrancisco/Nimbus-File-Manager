package br.com.jorgemelo.nimbusfilemanager.settings.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.ExternalToolStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Tools;

/**
 * The installer unpacks a published archive shaped like the real one: the
 * executables live under a wrapper folder, next to headers, docs and ffplay
 * that this application never calls.
 */
class ExternalToolInstallerTest {

	private static final String ROOT = "ffmpeg-master-latest-win64-gpl-shared/";

	private final AppSettingService appSettingService = mock(AppSettingService.class);

	private ExternalToolInstaller installer(Path tools, Path archive) {
		return installer(tools, target -> copyInto(archive, target));
	}

	private ExternalToolInstaller installer(Path tools, ExternalToolArchiveSource source) {
		when(appSettingService.stringValue(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));

		ExternalToolPaths paths = new ExternalToolPaths(appSettingService,
				new NimbusFileManagerProperties(null, new Tools("", "", true), null, null, null, null), tools);

		return new ExternalToolInstaller(paths, source, this::runsWhenOnDisk, new ExternalToolInstallProgress());
	}

	/** Answers a version for any file that is really there, as the probe does. */
	private Optional<String> runsWhenOnDisk(String executable) {
		return Files.isRegularFile(Path.of(executable)) ? Optional.of("ffmpeg version 8.0-test") : Optional.empty();
	}

	private Path copyInto(Path archive, Path targetFolder) {
		try {
			Path copy = targetFolder.resolve("downloaded.zip");

			Files.copy(archive, copy);

			return copy;
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * @param entries names inside the archive; each gets its own name as content so
	 * a misplaced file is visible in the assertion.
	 */
	private Path archive(Path folder, List<String> entries) throws IOException {
		Path archive = folder.resolve("build.zip");

		try (OutputStream file = Files.newOutputStream(archive); ZipOutputStream zip = new ZipOutputStream(file)) {
			for (String entry : entries) {
				zip.putNextEntry(new ZipEntry(entry));

				// An entry named EMPTY carries no bytes, as a placeholder in the published
				// archive would: its size never contributes to the progress total.
				if (!entry.endsWith("EMPTY.dll")) {
					zip.write(entry.getBytes(StandardCharsets.UTF_8));
				}

				zip.closeEntry();
			}
		}

		return archive;
	}

	@Test
	void unpacksTheExecutablesAndTheirLibrariesFlattenedIntoTheToolsFolder(@TempDir Path temp) throws IOException {
		Path tools = temp.resolve("tools").resolve("bin");

		Path archive = archive(temp, List.of(ROOT, ROOT + "bin/", ROOT + "bin/ffmpeg.exe", ROOT + "bin/ffprobe.exe",
				ROOT + "bin/avcodec-63.dll", ROOT + "bin/placeholder-EMPTY.dll", ROOT + "LICENSE.txt"));

		ExternalToolStatus status = installer(tools, archive).install();

		Assertions.assertThat(tools.resolve("ffmpeg.exe")).exists();
		Assertions.assertThat(tools.resolve("ffprobe.exe")).exists();
		Assertions.assertThat(tools.resolve("avcodec-63.dll")).exists();
		Assertions.assertThat(tools.resolve("FFMPEG-LICENSE.txt")).exists();

		Assertions.assertThat(status.complete()).isTrue();
		Assertions.assertThat(status.bundled()).isTrue();
		Assertions.assertThat(status.version()).isEqualTo("ffmpeg version 8.0-test");
	}

	/** Headers, docs and the player are dead weight: none of them is called. */
	@Test
	void leavesBehindWhatTheApplicationNeverCalls(@TempDir Path temp) throws IOException {
		Path tools = temp.resolve("bin");

		Path archive = archive(temp, List.of(ROOT + "bin/ffmpeg.exe", ROOT + "bin/ffprobe.exe", ROOT + "bin/ffplay.exe",
				ROOT + "include/libavutil/avutil.h", ROOT + "doc/ffmpeg.html"));

		installer(tools, archive).install();

		Assertions.assertThat(tools.resolve("ffplay.exe")).doesNotExist();
		Assertions.assertThat(tools.resolve("avutil.h")).doesNotExist();
		Assertions.assertThat(tools.resolve("ffmpeg.html")).doesNotExist();
	}

	/**
	 * A crafted entry must not write outside the tools folder. Taking only the file
	 * name is what makes the traversal impossible, so the file lands inside instead
	 * of escaping.
	 */
	@Test
	void cannotBeMadeToWriteOutsideTheToolsFolder(@TempDir Path temp) throws IOException {
		Path tools = temp.resolve("bin");

		Path archive = archive(temp, List.of(ROOT + "bin/ffmpeg.exe", "../../escaped.dll"));

		installer(tools, archive).install();

		Assertions.assertThat(tools.resolve("escaped.dll")).exists();
		Assertions.assertThat(temp.resolve("escaped.dll")).doesNotExist();
		Assertions.assertThat(temp.getParent().resolve("escaped.dll")).doesNotExist();
	}

	/** The archive is working material: it must not stay behind taking ~70 MB. */
	@Test
	void removesTheArchiveOnceUnpacked(@TempDir Path temp) throws IOException {
		Path tools = temp.resolve("bin");

		installer(tools, archive(temp, List.of(ROOT + "bin/ffmpeg.exe"))).install();

		Assertions.assertThat(tools.resolve("downloaded.zip")).doesNotExist();
	}

	/**
	 * The cleanup in the finally block must never throw over the failure that got
	 * it there: what the caller sees is why the install failed, not why a leftover
	 * could not be removed.
	 */
	@Test
	void reportsTheInstallFailureEvenWhenTheLeftoverCannotBeRemoved(@TempDir Path temp) throws IOException {
		Path undeletable = temp.resolve("not-an-archive");

		Files.createDirectories(undeletable);
		Files.writeString(undeletable.resolve("occupant.txt"), "keeps the folder non-empty");

		ExternalToolInstaller installer = installer(temp.resolve("bin"), _ -> undeletable);

		Assertions.assertThatIllegalStateException().isThrownBy(installer::install)
				.withMessageContaining("Could not install");

		Assertions.assertThat(undeletable).exists();
	}

	@Test
	void reportsThatNothingIsInstalledBeforeTheFirstInstall(@TempDir Path temp) throws IOException {
		ExternalToolStatus status = installer(temp.resolve("bin"), archive(temp, List.of())).status();

		Assertions.assertThat(status.complete()).isFalse();
		Assertions.assertThat(status.bundled()).isFalse();
		Assertions.assertThat(status.version()).isNull();
		Assertions.assertThat(status.ffmpegPath()).isEqualTo("ffmpeg");
	}

	@Test
	void failsLoudlyWhenTheArchiveIsNotAZip(@TempDir Path temp) throws IOException {
		Path tools = temp.resolve("bin");

		Path broken = temp.resolve("broken.zip");

		Files.writeString(broken, "not an archive");

		ExternalToolInstaller installer = installer(tools, broken);

		Assertions.assertThatIllegalStateException().isThrownBy(installer::install)
				.withMessageContaining("Could not install");
	}
}