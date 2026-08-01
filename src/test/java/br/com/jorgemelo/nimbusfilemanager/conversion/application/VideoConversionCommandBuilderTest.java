package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.CommandOptions;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionQuality;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.VideoEncoder;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolPaths;

class VideoConversionCommandBuilderTest {

	private final ExternalToolPaths externalToolPaths = mock(ExternalToolPaths.class);
	private final HardwareEncoderProbe hardwareEncoderProbe = mock(HardwareEncoderProbe.class);
	private final VideoConversionCommandBuilder builder = new VideoConversionCommandBuilder(externalToolPaths,
			hardwareEncoderProbe);

	private Path input;
	private Path output;

	@BeforeEach
	void setUp(@TempDir Path library) {
		when(externalToolPaths.ffmpeg()).thenReturn("ffmpeg");

		// Absolute paths, because the command carries the input and output resolved
		// against the working directory: a relative one would render differently on
		// Windows and on the Linux CI.
		input = library.resolve("clip.mkv");
		output = library.resolve("conversion").resolve("clip.mp4");
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

		CommandOptions high = new CommandOptions(ConversionQuality.HIGH_QUALITY, false, false, true, true);

		Assertions.assertThat(builder.build(input, output, high)).containsSubsequence("-crf", "18")
				.containsSubsequence("-preset", "medium");
	}

	@Test
	void remuxesInsteadOfReEncodingAVideoThatIsAlreadyHevc() {
		List<String> command = builder.build(input, output, options(true, false, true));

		Assertions.assertThat(command).containsSubsequence("-c:v", "copy").doesNotContain("libx265", "-crf", "-preset");
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

		Assertions.assertThat(command).doesNotContain("0:s?", "-c:s", "mov_text").containsSubsequence("-map", "0:a?")
				.containsSubsequence("-c:d", "copy");
	}

	@Test
	void alwaysTagsHevcAsHvc1SoAppleAndWindowsPlayersAcceptTheMp4() {
		Assertions.assertThat(builder.build(input, output, options(false, false, true)))
				.containsSubsequence("-tag:v", "hvc1").containsSubsequence("-movflags", "use_metadata_tags");

		Assertions.assertThat(builder.build(input, output, options(true, false, true))).containsSubsequence("-tag:v",
				"hvc1");
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

	/**
	 * The retry that follows a container refusing the camera's telemetry: the same
	 * command, without the data tracks that cannot travel into MP4.
	 */
	@Test
	void leavesTheDataTracksOutWhenTheAttemptAsksForIt() {
		CommandOptions withoutData = new CommandOptions(ConversionQuality.BALANCED, false, false, true, false);

		Assertions.assertThat(builder.build(input, output, withoutData)).doesNotContain("0:d?");
		Assertions.assertThat(builder.build(input, output, options(false, false, true))).contains("0:d?");
	}

	private CommandOptions options(boolean copyVideo, boolean encodeAudioAsAac, boolean includeSubtitles) {
		return new CommandOptions(ConversionQuality.BALANCED, copyVideo, encodeAudioAsAac, includeSubtitles, true);
	}

	/**
	 * The user picks "rápido", not a vendor: the command is built for whichever
	 * encoder this machine proved it has, each with its own quality knob. Naming
	 * one here would make the feature work on one card and fail on the next.
	 */
	@Test
	void usesWhicheverGraphicsCardEncoderTheMachineHasWithItsOwnQualityScale() {
		when(hardwareEncoderProbe.hardwareEncoder()).thenReturn(Optional.of(VideoEncoder.QUICK_SYNC));

		CommandOptions fast = new CommandOptions(ConversionQuality.FAST_BALANCED, false, false, true, true);

		Assertions.assertThat(builder.build(input, output, fast)).containsSubsequence("-c:v", "hevc_qsv")
				.containsSubsequence("-global_quality", "22").containsSubsequence("-look_ahead", "1")
				.containsSubsequence("-tag:v", "hvc1").doesNotContain("-crf", "libx265");

		when(hardwareEncoderProbe.hardwareEncoder()).thenReturn(Optional.of(VideoEncoder.NVENC));

		Assertions.assertThat(builder.build(input, output, fast)).containsSubsequence("-c:v", "hevc_nvenc")
				.containsSubsequence("-cq", "22").doesNotContain("-global_quality", "-crf");

		when(hardwareEncoderProbe.hardwareEncoder()).thenReturn(Optional.of(VideoEncoder.AMF));

		Assertions.assertThat(builder.build(input, output, fast)).containsSubsequence("-c:v", "hevc_amf")
				.containsSubsequence("-qp_i", "22").doesNotContain("-global_quality", "-cq");
	}

	/**
	 * A preference outlives the machine it was chosen on: the same stored choice
	 * lands on a computer with no usable card, and must still produce a command
	 * ffmpeg can run instead of failing every file of the batch.
	 */
	@Test
	void fallsBackToTheSoftwareEncoderWhenTheMachineHasNoUsableCard() {
		when(hardwareEncoderProbe.hardwareEncoder()).thenReturn(Optional.empty());

		CommandOptions fast = new CommandOptions(ConversionQuality.FAST_BALANCED, false, false, true, true);

		Assertions.assertThat(builder.build(input, output, fast)).containsSubsequence("-c:v", "libx265")
				.containsSubsequence("-crf", "22");
	}
}