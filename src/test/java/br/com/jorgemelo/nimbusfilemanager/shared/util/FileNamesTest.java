package br.com.jorgemelo.nimbusfilemanager.shared.util;

import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileNamesTest {

	@Test
	void keepsTheDesiredNameWhenNothingOccupiesIt(@TempDir Path folder) {
		Path desired = folder.resolve("clip.mp4");

		Assertions.assertThat(FileNames.nextAvailable(desired)).isEqualTo(desired);
	}

	@Test
	void numbersTheNameUntilOneIsFree(@TempDir Path folder) throws Exception {
		Files.writeString(folder.resolve("clip.mp4"), "a");
		Files.writeString(folder.resolve("clip (1).mp4"), "b");

		Assertions.assertThat(FileNames.nextAvailable(folder.resolve("clip.mp4")))
				.isEqualTo(folder.resolve("clip (2).mp4"));
	}

	@Test
	void insertsTheSuffixBeforeTheExtension(@TempDir Path folder) {
		Assertions.assertThat(FileNames.withSuffix(folder.resolve("clip.mp4"), " (H.265)"))
				.isEqualTo(folder.resolve("clip (H.265).mp4"));
	}

	@Test
	void appendsTheSuffixWhenThereIsNoExtension(@TempDir Path folder) {
		Assertions.assertThat(FileNames.withSuffix(folder.resolve("clip"), " (H.265)"))
				.isEqualTo(folder.resolve("clip (H.265)"));
	}

	@Test
	void replacesTheExtensionKeepingFolderAndBaseName(@TempDir Path folder) {
		Assertions.assertThat(FileNames.withExtension(folder.resolve("clip.avi"), "mkv"))
				.isEqualTo(folder.resolve("clip.mkv"));
		Assertions.assertThat(FileNames.withExtension(folder.resolve("clip.avi"), ".MKV"))
				.isEqualTo(folder.resolve("clip.mkv"));
	}

	@Test
	void dropsTheExtensionWhenTheNewOneIsBlank(@TempDir Path folder) {
		Assertions.assertThat(FileNames.withExtension(folder.resolve("clip.avi"), " "))
				.isEqualTo(folder.resolve("clip"));
	}

	@Test
	void treatsALeadingDotAsPartOfTheNameNotAnExtension() {
		Assertions.assertThat(FileNames.baseName(".gitignore")).isEqualTo(".gitignore");
		Assertions.assertThat(FileNames.withSuffix(Path.of(".gitignore"), " (2)")).isEqualTo(Path.of(".gitignore (2)"));
	}

	@Test
	void keepsEverythingBeforeTheLastDotAsTheBaseName() {
		Assertions.assertThat(FileNames.baseName("backup.tar.gz")).isEqualTo("backup.tar");
		Assertions.assertThat(FileNames.baseName("clip")).isEqualTo("clip");
	}

	@Test
	void resolvesAgainstTheCurrentDirectoryWhenThePathHasNoParent() {
		Assertions.assertThat(FileNames.withExtension(Path.of("clip.avi"), "mkv")).isEqualTo(Path.of("clip.mkv"));
		Assertions.assertThat(FileNames.nextAvailable(Path.of("clip.avi"))).isEqualTo(Path.of("clip.avi"));
	}
}