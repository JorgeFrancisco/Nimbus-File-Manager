package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionOptions;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.AudioHandling;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionQuality;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.NameAffixPosition;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.OriginalDisposition;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;

class ConversionFileNamingTest {

	@TempDir
	private Path workspace;

	private final WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
	private final ConversionFileNaming naming = new ConversionFileNaming(workspaceManager);

	ConversionFileNamingTest() {
		when(workspaceManager.temp()).thenAnswer(_ -> workspace.resolve("temp"));
	}

	@Test
	void putsTheAffixAtTheEndOfTheNameByDefault() {
		Path source = Path.of("D:", "library", "clip.mkv");

		Assertions.assertThat(naming.finalName(source, ConversionOptions.defaults()))
				.isEqualTo(Path.of("D:", "library", "clip_H265.mp4"));
	}

	@Test
	void putsTheAffixInFrontWhenAskedTo() {
		Path source = Path.of("D:", "library", "clip.mkv");

		Assertions.assertThat(naming.finalName(source, options("HEVC - ", NameAffixPosition.PREFIX)))
				.isEqualTo(Path.of("D:", "library", "HEVC - clip.mp4"));
	}

	@Test
	void keepsTheSourceNameWhenNoAffixWasConfigured() {
		Path source = Path.of("D:", "library", "clip.mkv");

		Assertions.assertThat(naming.finalName(source, options("", NameAffixPosition.SUFFIX)))
				.isEqualTo(Path.of("D:", "library", "clip.mp4"));
		Assertions.assertThat(naming.finalName(source, options("   ", NameAffixPosition.SUFFIX)))
				.isEqualTo(Path.of("D:", "library", "clip.mp4"));
	}

	@Test
	void stripsWhatAFileNameCannotHold() {
		Assertions.assertThat(naming.affix(options("a/b\\c:d*e?f\"g<h>i|j", NameAffixPosition.SUFFIX)))
				.isEqualTo("abcdefghij");
		// Spaces survive: a prefix like "HEVC - " needs its trailing one, and the
		// extension always follows the affix.
		Assertions.assertThat(naming.affix(options(" _H265 ", NameAffixPosition.SUFFIX))).isEqualTo(" _H265 ");
	}

	@Test
	void cutsAnAffixThatWouldBlowUpThePath() {
		String longAffix = "_".repeat(60);

		Assertions.assertThat(naming.affix(options(longAffix, NameAffixPosition.SUFFIX))).hasSize(40);
	}

	@Test
	void treatsAnAffixLeftWithNothingUsableAsNoAffixAtAll() {
		Assertions.assertThat(naming.affix(options("///", NameAffixPosition.SUFFIX))).isEmpty();
		Assertions.assertThat(naming.affix(options(null, NameAffixPosition.SUFFIX))).isEmpty();
	}

	/**
	 * The encode belongs to the application, not to the library: a sync client
	 * mirroring the library uploads a half-written encode as if it were media and
	 * reverts the rename that puts the finished file in place.
	 */
	@Test
	void encodesInsideTheWorkspaceUnderTheNameTheFileWillCarry(@TempDir Path folder) {
		Path source = folder.resolve("clip.mkv");

		Path temporary = naming.temporaryFor(source, ConversionOptions.defaults());

		Assertions.assertThat(temporary).hasParentRaw(workspace.resolve("temp").resolve("conversion"));
		Assertions.assertThat(temporary.getFileName()).hasToString("clip_H265.mp4");
		Assertions.assertThat(temporary.getParent()).isDirectory();
	}

	/**
	 * A file sitting where the work folder should be, or a workspace pointed at
	 * something unwritable: the encode cannot start, and the reason has to name the
	 * folder instead of failing later as a mysterious missing output.
	 */
	@Test
	void refusesToEncodeWhenTheWorkFolderCannotBeCreated(@TempDir Path folder) throws Exception {
		Files.createDirectories(workspace.resolve("temp"));
		Files.writeString(workspace.resolve("temp").resolve("conversion"), "a file in the way");

		Path source = folder.resolve("clip.mkv");

		ConversionOptions options = ConversionOptions.defaults();

		Assertions.assertThatThrownBy(() -> naming.temporaryFor(source, options))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("conversion");
	}

	/**
	 * Two sources with the same name, from different folders of the library, share
	 * one work folder now.
	 */
	@Test
	void neverReusesAWorkNameThatIsAlreadyOnDisk(@TempDir Path folder) throws Exception {
		Path source = folder.resolve("clip.mkv");

		Files.createDirectories(workspace.resolve("temp").resolve("conversion"));
		Files.writeString(workspace.resolve("temp").resolve("conversion").resolve("clip_H265.mp4"), "leftover");

		Assertions.assertThat(naming.temporaryFor(source, ConversionOptions.defaults()).getFileName())
				.hasToString("clip_H265 (1).mp4");
	}

	@Test
	void discardsTheTemporaryFileAndSurvivesThereBeingNothingToDiscard(@TempDir Path folder) throws Exception {
		Path temporary = Files.writeString(folder.resolve("clip_temp.tmp"), "partial");

		naming.discard(temporary);
		naming.discard(temporary);
		naming.discard(null);

		Assertions.assertThat(temporary).doesNotExist();
	}

	@Test
	void neverBreaksTheBatchWhenTheTemporaryFileCannotBeDeleted(@TempDir Path folder) throws Exception {
		// Something took the temporary path over (a folder here, an antivirus lock in
		// the field): cleaning up is best-effort and must never throw, or one stray
		// file would cost the whole batch.
		Path taken = Files.createDirectories(folder.resolve("clip_temp.tmp"));

		Files.writeString(taken.resolve("inside.txt"), "content");

		naming.discard(taken);

		Assertions.assertThat(taken).exists();
	}

	private ConversionOptions options(String affix, NameAffixPosition position) {
		return new ConversionOptions(ConversionQuality.BALANCED, AudioHandling.AUTO, OriginalDisposition.KEEP, affix,
				position);
	}
}