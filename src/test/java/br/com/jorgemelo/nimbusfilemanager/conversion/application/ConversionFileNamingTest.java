package br.com.jorgemelo.nimbusfilemanager.conversion.application;

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

class ConversionFileNamingTest {

	private final ConversionFileNaming naming = new ConversionFileNaming();

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

	@Test
	void encodesNextToTheSourceUnderAnExtensionTheInventorySkips(@TempDir Path folder) {
		Path source = folder.resolve("clip.mkv");

		Path temporary = naming.temporaryFor(source, ConversionOptions.defaults());

		Assertions.assertThat(temporary).hasParentRaw(folder);
		Assertions.assertThat(temporary.getFileName()).hasToString("clip_H265_temp.tmp");
	}

	@Test
	void neverReusesATemporaryNameThatIsAlreadyOnDisk(@TempDir Path folder) throws Exception {
		Path source = folder.resolve("clip.mkv");

		Files.writeString(folder.resolve("clip_H265_temp.tmp"), "leftover");

		Assertions.assertThat(naming.temporaryFor(source, ConversionOptions.defaults()).getFileName())
				.hasToString("clip_H265_temp (1).tmp");
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