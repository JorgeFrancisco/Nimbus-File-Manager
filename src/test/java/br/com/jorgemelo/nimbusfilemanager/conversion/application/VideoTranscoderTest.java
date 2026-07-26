package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.BooleanSupplier;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.OriginalDisposition;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.NameAffixPosition;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionOptions;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.TranscodeExecution;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.TranscodeRequest;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.TranscodeResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.AudioHandling;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionFailure;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionQuality;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ExternalToolGate;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ProcessingMetrics;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolPaths;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.ProcessingProperties;

class VideoTranscoderTest {

	private static final TranscodeExecution SUCCESS = new TranscodeExecution(true, 0, "");
	private static final TranscodeExecution AUDIO_REJECTED = new TranscodeExecution(true, 1,
			"Could not find tag for codec pcm_s16le");
	private static final TranscodeExecution SUBTITLES_REJECTED = new TranscodeExecution(true, 1,
			"Could not find tag for codec hdmv_pgs_subtitle in stream #2");

	private final ConvertedVideoValidator validator = mock(ConvertedVideoValidator.class);
	private final ConversionFileNaming conversionFileNaming = mock(ConversionFileNaming.class);
	private final ExternalToolPaths externalToolPaths = mock(ExternalToolPaths.class);

	private final Path source = Path.of("D:", "library", "clip.mkv");
	private final Path output = Path.of("D:", "workspace", "conversion", "clip.mp4");

	private final List<List<String>> commands = new ArrayList<>();

	VideoTranscoderTest() {
		when(externalToolPaths.ffmpeg()).thenReturn("ffmpeg");
		when(validator.validate(any(), any())).thenReturn(Optional.empty());
	}

	@Test
	void convertsAndReturnsTheValidatedWorkspaceFile() {
		stubNaming();

		TranscodeResult result = transcoder(_ -> SUCCESS).transcode(request(AudioHandling.COPY), _ -> {
		}, notCancelled());

		Assertions.assertThat(result.successful()).isTrue();
		Assertions.assertThat(result.output()).isEqualTo(output);
		Assertions.assertThat(result.audioFallback()).isFalse();

		Assertions.assertThat(commands).hasSize(1);
		Assertions.assertThat(commands.getFirst()).containsSubsequence("-c:a", "copy");

		verify(conversionFileNaming, never()).discard(any());
	}

	@Test
	void encodesTheAudioUpFrontWhenTheUserAskedForAac() {
		stubNaming();

		transcoder(_ -> SUCCESS).transcode(request(AudioHandling.AAC), _ -> {
		}, notCancelled());

		Assertions.assertThat(commands.getFirst()).containsSubsequence("-c:a", "aac");
	}

	@Test
	void retriesWithAacWhenTheContainerRejectedTheOriginalAudio() {
		stubNaming();

		TranscodeResult result = transcoder(attempt -> attempt == 1 ? AUDIO_REJECTED : SUCCESS)
				.transcode(request(AudioHandling.AUTO), _ -> {
				}, notCancelled());

		Assertions.assertThat(result.successful()).isTrue();
		Assertions.assertThat(result.audioFallback()).isTrue();

		Assertions.assertThat(commands).hasSize(2);
		Assertions.assertThat(commands.get(0)).containsSubsequence("-c:a", "copy");
		Assertions.assertThat(commands.get(1)).containsSubsequence("-c:a", "aac");
	}

	@Test
	void doesNotRetryAFailureAnotherAudioCodecCannotFix() {
		stubNaming();

		TranscodeResult result = transcoder(_ -> new TranscodeExecution(true, 1, "No space left on device"))
				.transcode(request(AudioHandling.AUTO), _ -> {
				}, notCancelled());

		Assertions.assertThat(result.successful()).isFalse();
		Assertions.assertThat(result.failure()).isEqualTo(ConversionFailure.ENCODER_FAILED);
		Assertions.assertThat(result.audioFallback()).isFalse();

		Assertions.assertThat(commands).hasSize(1);

		verify(conversionFileNaming).discard(output);
	}

	@Test
	void discardsTheOutputWhenValidationRejectsIt() {
		stubNaming();

		when(validator.validate(output, 120.0)).thenReturn(Optional.of(ConversionFailure.NOT_HEVC));

		TranscodeResult result = transcoder(_ -> SUCCESS).transcode(request(AudioHandling.COPY), _ -> {
		}, notCancelled());

		Assertions.assertThat(result.successful()).isFalse();
		Assertions.assertThat(result.failure()).isEqualTo(ConversionFailure.NOT_HEVC);

		verify(conversionFileNaming).discard(output);
	}

	@Test
	void reportsTheProgressOfTheCurrentFileWhileFfmpegRuns() {
		stubNaming();

		List<Integer> reported = new ArrayList<>();

		VideoTranscoder transcoder = transcoderReporting(List.of("frame=1", "out_time_us=60000000", "progress=end"));

		transcoder.transcode(request(AudioHandling.COPY), reported::add, notCancelled());

		Assertions.assertThat(reported).containsExactly(50);
	}

	@Test
	void stopsAndDiscardsThePartialFileWhenTheBatchIsCancelled() {
		stubNaming();

		TranscodeResult result = transcoder(_ -> new TranscodeExecution(false, -1, "FFmpeg was stopped on request"))
				.transcode(request(AudioHandling.AUTO), _ -> {
				}, () -> true);

		Assertions.assertThat(result.successful()).isFalse();
		Assertions.assertThat(result.failure()).isEqualTo(ConversionFailure.CANCELLED);

		// A cancelled batch never spends a second full encode on a retry.
		Assertions.assertThat(commands).hasSize(1);

		verify(conversionFileNaming).discard(output);
		verify(validator, never()).validate(any(), any());
	}

	@Test
	void treatsAnFfmpegThatCouldNotBeStartedAsAFailedConversion() {
		stubNaming();

		VideoTranscoder transcoder = build((_, _, _) -> {
			throw new IOException("ffmpeg is missing");
		});

		TranscodeResult result = transcoder.transcode(request(AudioHandling.COPY), _ -> {
		}, notCancelled());

		Assertions.assertThat(result.successful()).isFalse();
		Assertions.assertThat(result.failure()).isEqualTo(ConversionFailure.ENCODER_FAILED);

		verify(conversionFileNaming).discard(output);
	}

	@Test
	void treatsAKilledProcessAsAFailedConversion() {
		stubNaming();

		TranscodeResult result = transcoder(_ -> new TranscodeExecution(false, -1, "timed out"))
				.transcode(request(AudioHandling.COPY), _ -> {
				}, notCancelled());

		Assertions.assertThat(result.successful()).isFalse();
		Assertions.assertThat(result.failure()).isEqualTo(ConversionFailure.ENCODER_FAILED);
	}

	@Test
	void stopsAndKeepsTheThreadInterruptedWhenTheBatchIsCancelled() {
		stubNaming();

		VideoTranscoder transcoder = build((_, _, _) -> {
			throw new InterruptedException("cancelled");
		});

		TranscodeResult result = transcoder.transcode(request(AudioHandling.COPY), _ -> {
		}, notCancelled());

		Assertions.assertThat(result.successful()).isFalse();
		Assertions.assertThat(result.failure()).isEqualTo(ConversionFailure.ENCODER_FAILED);

		// The interrupt is re-raised so whoever is cancelling the batch still sees it.
		Assertions.assertThat(Thread.interrupted()).isTrue();
	}

	@Test
	void dropsTheSubtitlesWhenMp4CannotHoldThemAndSaysSo() {
		stubNaming();

		TranscodeResult result = transcoder(attempt -> attempt == 1 ? SUBTITLES_REJECTED : SUCCESS)
				.transcode(request(AudioHandling.COPY), _ -> {
				}, notCancelled());

		Assertions.assertThat(result.successful()).isTrue();
		Assertions.assertThat(result.subtitlesDropped()).isTrue();
		Assertions.assertThat(result.audioFallback()).isFalse();

		Assertions.assertThat(commands).hasSize(2);
		Assertions.assertThat(commands.get(0)).contains("0:s?");
		Assertions.assertThat(commands.get(1)).doesNotContain("0:s?");
	}

	@Test
	void remuxesInsteadOfReEncodingWhenTheSourceIsAlreadyHevc() {
		stubNaming();

		TranscodeRequest remux = new TranscodeRequest(source, 120.0, new ConversionOptions(ConversionQuality.BALANCED,
				AudioHandling.COPY, OriginalDisposition.KEEP, "", NameAffixPosition.SUFFIX), true);

		TranscodeResult result = transcoder(_ -> SUCCESS).transcode(remux, _ -> {
		}, notCancelled());

		Assertions.assertThat(result.successful()).isTrue();
		Assertions.assertThat(commands.getFirst()).containsSubsequence("-c:v", "copy");
	}

	private void stubNaming() {
		when(conversionFileNaming.temporaryFor(eq(source), any())).thenReturn(output);
	}

	private TranscodeRequest request(AudioHandling audio) {
		return new TranscodeRequest(source, 120.0, new ConversionOptions(ConversionQuality.BALANCED, audio,
				OriginalDisposition.KEEP, "", NameAffixPosition.SUFFIX), false);
	}

	private BooleanSupplier notCancelled() {
		return () -> false;
	}

	/** A runner whose answer depends on which attempt (1-based) is running. */
	private VideoTranscoder transcoder(IntFunction<TranscodeExecution> answers) {
		return build((command, _, _) -> {
			commands.add(command);

			return answers.apply(commands.size());
		});
	}

	private VideoTranscoder transcoderReporting(List<String> progressLines) {
		return build((command, progress, _) -> {
			commands.add(command);

			progressLines.forEach(progress);

			return SUCCESS;
		});
	}

	private VideoTranscoder build(VideoTranscodeRunner runner) {
		return new VideoTranscoder(new VideoConversionCommandBuilder(externalToolPaths), runner, validator,
				new StreamCompatibilityPolicy(), conversionFileNaming, new FfmpegProgressParser(),
				new ExternalToolGate(new ProcessingProperties(1, 8, 1, 1, 1, 1), new ProcessingMetrics()));
	}
}