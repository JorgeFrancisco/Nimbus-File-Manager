package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.PlacedConversion;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionOptions;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.AudioHandling;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionQuality;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.NameAffixPosition;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.OriginalDisposition;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.MoveIntegrityException;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationMoveVerifier;
import br.com.jorgemelo.nimbusfilemanager.organization.application.SecureFileMove;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MoveBaseline;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWriteOff;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import br.com.jorgemelo.nimbusfilemanager.shared.application.library.LibraryFileMutations;
import br.com.jorgemelo.nimbusfilemanager.organization.application.SecureLibraryFiles;

class ConversionFilePlacementTest {

	/** Any execution: what it names is the announcement, not the placement. */
	private static final Long EXECUTION_ID = 1L;

	private final SelfWrittenPathRegistry pathRegistry = new SelfWriteOff();
	private final ConversionFileNaming conversionFileNaming = new ConversionFileNaming(mock(WorkspaceManager.class));
	private final ConversionFilePlacement placement = new ConversionFilePlacement(
			new SecureLibraryFiles(
					new SecureFileMove(new OrganizationMoveVerifier(new FileHashService()), pathRegistry),
					pathRegistry),
			conversionFileNaming);

	private final ConversionOptions noAffix = new ConversionOptions(ConversionQuality.BALANCED, AudioHandling.AUTO,
			OriginalDisposition.KEEP, "", NameAffixPosition.SUFFIX);

	@Test
	void suffixesTheConvertedFileWhileTheOriginalStillOccupiesTheName(@TempDir Path tmp) throws Exception {
		Path library = Files.createDirectories(tmp.resolve("library"));
		Path source = Files.writeString(library.resolve("clip.mp4"), "original");
		Path converted = convertedFile(tmp, "clip.mp4");

		PlacedConversion placed = placement.place(converted, source, noAffix, EXECUTION_ID);

		Assertions.assertThat(placed.path()).isEqualTo(library.resolve("clip (H.265).mp4")).hasContent("converted");
		Assertions.assertThat(source).hasContent("original");
		Assertions.assertThat(converted).doesNotExist();
	}

	@Test
	void takesTheSourceNameWhenTheContainerChangedAndTheNameIsFree(@TempDir Path tmp) throws Exception {
		Path library = Files.createDirectories(tmp.resolve("library"));
		Path source = Files.writeString(library.resolve("clip.avi"), "original");
		Path converted = convertedFile(tmp, "clip_temp.tmp");

		PlacedConversion placed = placement.place(converted, source, noAffix, EXECUTION_ID);

		// The MKV became an MP4, so the name it wants is free and no suffix is needed.
		Assertions.assertThat(placed.path()).isEqualTo(library.resolve("clip.mp4")).hasContent("converted");
		Assertions.assertThat(source).exists();
	}

	@Test
	void givesTheConvertedFileTheNameTheAffixAsksFor(@TempDir Path tmp) throws Exception {
		Path library = Files.createDirectories(tmp.resolve("library"));
		Path source = Files.writeString(library.resolve("clip.mp4"), "original");
		Path converted = convertedFile(tmp, "clip_H265_temp.tmp");

		ConversionOptions suffixed = new ConversionOptions(ConversionQuality.BALANCED, AudioHandling.AUTO,
				OriginalDisposition.KEEP, "_H265", NameAffixPosition.SUFFIX);

		Assertions.assertThat(placement.place(converted, source, suffixed, EXECUTION_ID).path())
				.isEqualTo(library.resolve("clip_H265.mp4")).hasContent("converted");
		Assertions.assertThat(source).hasContent("original");
	}

	@Test
	void putsTheAffixInFrontWhenThatIsWhereTheUserWantsIt(@TempDir Path tmp) throws Exception {
		Path library = Files.createDirectories(tmp.resolve("library"));
		Path source = Files.writeString(library.resolve("clip.mp4"), "original");
		Path converted = convertedFile(tmp, "H265_clip_temp.tmp");

		ConversionOptions prefixed = new ConversionOptions(ConversionQuality.BALANCED, AudioHandling.AUTO,
				OriginalDisposition.KEEP, "H265_", NameAffixPosition.PREFIX);

		Assertions.assertThat(placement.place(converted, source, prefixed, EXECUTION_ID).path())
				.isEqualTo(library.resolve("H265_clip.mp4"));
	}

	@Test
	void numbersTheSuffixedNameRatherThanOverwritingAPreviousConversion(@TempDir Path tmp) throws Exception {
		Path library = Files.createDirectories(tmp.resolve("library"));
		Path source = Files.writeString(library.resolve("clip.mp4"), "original");

		Files.writeString(library.resolve("clip (H.265).mp4"), "previous");

		PlacedConversion placed = placement.place(convertedFile(tmp, "clip.mp4"), source, noAffix, EXECUTION_ID);

		Assertions.assertThat(placed.path()).isEqualTo(library.resolve("clip (H.265) (1).mp4"));
		Assertions.assertThat(library.resolve("clip (H.265).mp4")).hasContent("previous");
	}

	@Test
	void givesTheConvertedFileTheOriginalNameOnceTheOriginalIsGone(@TempDir Path tmp) throws Exception {
		Path library = Files.createDirectories(tmp.resolve("library"));
		Path source = library.resolve("clip.mp4");
		Path placed = Files.writeString(library.resolve("clip (H.265).mp4"), "converted");

		PlacedConversion renamed = placement.renameToOriginalName(placementOf(placed), source, EXECUTION_ID);

		Assertions.assertThat(renamed.path()).isEqualTo(source).hasContent("converted");
		Assertions.assertThat(placed).doesNotExist();
	}

	@Test
	void keepsTheConvertedNameWhenItAlreadyMatchesTheOriginal(@TempDir Path tmp) throws Exception {
		Path library = Files.createDirectories(tmp.resolve("library"));
		Path source = library.resolve("clip.avi");
		Path placed = Files.writeString(library.resolve("clip.mkv"), "converted");

		Assertions.assertThat(placement.renameToOriginalName(placementOf(placed), source, EXECUTION_ID).path())
				.isEqualTo(placed);
	}

	@Test
	void keepsTheSuffixedNameWhenTheOriginalNameIsStillTaken(@TempDir Path tmp) throws Exception {
		Path library = Files.createDirectories(tmp.resolve("library"));
		Path source = Files.writeString(library.resolve("clip.mp4"), "still here");
		Path placed = Files.writeString(library.resolve("clip (H.265).mp4"), "converted");

		Assertions.assertThat(placement.renameToOriginalName(placementOf(placed), source, EXECUTION_ID).path())
				.isEqualTo(placed);
		Assertions.assertThat(source).hasContent("still here");
	}

	@Test
	void keepsTheConvertedFileWhenTheCosmeticRenameFails(@TempDir Path tmp) throws Exception {
		Path library = Files.createDirectories(tmp.resolve("library"));
		Path source = library.resolve("clip.mp4");
		Path placed = Files.writeString(library.resolve("clip (H.265).mp4"), "converted");

		// A rename that physically happens but fails its integrity verify: the file is
		// still the user's converted video, so the conversion must not be lost over a
		// naming detail.
		OrganizationMoveVerifier verifier = mock(OrganizationMoveVerifier.class);

		when(verifier.capture(any())).thenReturn(new MoveBaseline(9L, "sha"));
		doThrow(new MoveIntegrityException("sha mismatch")).when(verifier).verify(any(), any(), any());

		ConversionFilePlacement failing = new ConversionFilePlacement(
				new SecureLibraryFiles(new SecureFileMove(verifier, pathRegistry), pathRegistry),
				conversionFileNaming);

		Assertions.assertThat(failing.renameToOriginalName(placementOf(placed), source, EXECUTION_ID).path())
				.isEqualTo(placed);
	}

	@Test
	void reportsAFailedPlacementToTheCaller(@TempDir Path tmp) throws Exception {
		Path library = Files.createDirectories(tmp.resolve("library"));
		Path source = Files.writeString(library.resolve("clip.mp4"), "original");

		// Nothing was ever encoded, so the move has no file to work with.
		Assertions.assertThatThrownBy(() -> placement.place(tmp.resolve("missing.mp4"), source, noAffix, EXECUTION_ID))
				.isInstanceOf(IOException.class);
	}

	/**
	 * A move that reports success without leaving the file behind has to be a
	 * failure, not a conversion: it happened once, and the batch counted the video
	 * as converted while the catalog pointed at a path with nothing behind it and
	 * the original had already gone to quarantine.
	 */
	@Test
	void refusesToCallThePlacementDoneWhenNothingLandedAtTheTarget(@TempDir Path tmp) throws Exception {
		Path library = Files.createDirectories(tmp.resolve("library"));
		Path source = Files.writeString(library.resolve("clip.mp4"), "original");
		Path converted = convertedFile(tmp, "clip.mp4");

		LibraryFileMutations silentlyLosingTheFile = mock(LibraryFileMutations.class);

		ConversionFilePlacement losing = new ConversionFilePlacement(silentlyLosingTheFile, conversionFileNaming);

		Assertions.assertThatThrownBy(() -> losing.place(converted, source, noAffix,
				EXECUTION_ID)).isInstanceOf(IOException.class);
	}

	private Path convertedFile(Path tmp, String fileName) throws IOException {
		Path workspace = Files.createDirectories(tmp.resolve("workspace").resolve("conversion"));

		return Files.writeString(workspace.resolve(fileName), "converted");
	}

	/**
	 * A file the test put in place by hand, dressed as what a real placement would
	 * have handed over. The digest is only carried through here; what these tests
	 * are about is where the file ends up and what name it keeps.
	 */
	private static PlacedConversion placementOf(Path path) {
		return new PlacedConversion(path, new MoveBaseline(1024L, "digest-proved-by-the-move"));
	}

}