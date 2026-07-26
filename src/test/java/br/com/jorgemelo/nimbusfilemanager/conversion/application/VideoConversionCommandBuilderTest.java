package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.CommandOptions;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionQuality;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolPaths;

class VideoConversionCommandBuilderTest {

	private final ExternalToolPaths externalToolPaths = mock(ExternalToolPaths.class);
	private final VideoConversionCommandBuilder builder = new VideoConversionCommandBuilder(externalToolPaths);

	private final Path input = Path.of("D:", "library", "clip.mkv");
	private final Path output = Path.of("D:", "workspace", "conversion", "clip.mp4");

	VideoConversionCommandBuilderTest() {
		when(externalToolPaths.ffmpeg()).thenReturn("ffmpeg");
	}

	@Test
	void encodesToHevcCarryingOverEveryStreamMp4CanHold() {
		List<String> command = builder.build(input, output, options(false, false, true));

		Assertions.assertThat(command).startsWith("ffmpeg").containsSubsequence("-i", input.toString())
				.containsSubsequence("-map", "0:V?").containsSubsequence("-map", "0:a?")
				.containsSubsequence("-map", "0:s?").containsSubsequence("-map", "0:d?")
				.containsSubsequence("-map_metadata", "0").containsSubsequence("-map_chapters", "0")
				.containsSubsequence("-c:v", "libx265").endsWith(output.toString());
	}

	@Test
	void mapsRealVideoStreamsOnlySoEmbeddedCoverArtIsNotEncodedAsVideo() {
		Assertions.assertThat(builder.build(input, output, options(false, false, true))).doesNotContain("0:v?");
	}

	@Test
	void appliesTheCrfAndPresetOfTheChosenProfileWithoutAskingTheUser() {
		Assertions.assertThat(builder.build(input, output, options(false, false, true)))
				.containsSubsequence("-crf", "22").containsSubsequence("-preset", "medium");

		CommandOptions high = new CommandOptions(ConversionQuality.HIGH_QUALITY, false, false, true);

		Assertions.assertThat(builder.build(input, output, high)).containsSubsequence("-crf", "18")
				.containsSubsequence("-preset", "medium");
	}

	@Test
	void remuxesInsteadOfReEncodingAVideoThatIsAlreadyHevc() {
		List<String> command = builder.build(input, output, options(true, false, true));

		Assertions.assertThat(command).containsSubsequence("-c:v", "copy").doesNotContain("libx265", "-crf",
				"-preset");
	}

	@Test
	void copiesTheAudioUnlessAacWasAskedFor() {
		Assertions.assertThat(builder.build(input, output, options(false, false, true)))
				.containsSubsequence("-c:a", "copy").doesNotContain("aac");

		Assertions.assertThat(builder.build(input, output, options(false, true, true)))
				.containsSubsequence("-c:a", "aac").containsSubsequence("-b:a", "192k");
	}

	@Test
	void convertsSubtitlesToTheOnlyFormatMp4Defines() {
		Assertions.assertThat(builder.build(input, output, options(false, false, true))).containsSubsequence("-c:s",
				"mov_text");
	}

	@Test
	void leavesSubtitlesOutEntirelyWhenMp4CannotHoldThem() {
		List<String> command = builder.build(input, output, options(false, false, false));

		Assertions.assertThat(command).doesNotContain("0:s?", "-c:s", "mov_text")
				.containsSubsequence("-map", "0:a?").containsSubsequence("-c:d", "copy");
	}

	@Test
	void alwaysTagsHevcAsHvc1SoAppleAndWindowsPlayersAcceptTheMp4() {
		Assertions.assertThat(builder.build(input, output, options(false, false, true)))
				.containsSubsequence("-tag:v", "hvc1").containsSubsequence("-movflags", "use_metadata_tags");

		Assertions.assertThat(builder.build(input, output, options(true, false, true)))
				.containsSubsequence("-tag:v", "hvc1");
	}

	@Test
	void asksFfmpegForMachineReadableProgressOnStdout() {
		Assertions.assertThat(builder.build(input, output, options(false, false, true)))
				.containsSubsequence("-progress", "pipe:1").contains("-nostats");
	}

	@Test
	void producesAnImmutableCommand() {
		List<String> command = builder.build(input, output, options(false, false, true));

		Assertions.assertThatThrownBy(() -> command.add("-extra")).isInstanceOf(UnsupportedOperationException.class);
	}

	private CommandOptions options(boolean copyVideo, boolean encodeAudioAsAac, boolean includeSubtitles) {
		return new CommandOptions(ConversionQuality.BALANCED, copyVideo, encodeAudioAsAac, includeSubtitles);
	}
}