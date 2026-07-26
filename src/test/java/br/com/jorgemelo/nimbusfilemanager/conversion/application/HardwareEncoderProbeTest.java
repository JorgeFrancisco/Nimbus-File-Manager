package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.TranscodeExecution;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.VideoEncoder;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolPaths;

/**
 * Which GPU encoder a machine really has. The whole point is that nothing is
 * assumed from the card's name or from the encoders ffmpeg was built with: a
 * build lists {@code hevc_nvenc} on laptops whose NVIDIA chip has no encoder
 * block at all, and only opening a session tells the truth.
 */
class HardwareEncoderProbeTest {

	private final ExternalToolPaths externalToolPaths = mock(ExternalToolPaths.class);
	private final VideoTranscodeRunner transcodeRunner = mock(VideoTranscodeRunner.class);
	private final HardwareEncoderProbe probe = new HardwareEncoderProbe(externalToolPaths, transcodeRunner);

	@BeforeEach
	void toolPath() {
		// The probe builds a command with it, and a null there would break the command
		// before any encoder was ever asked.
		when(externalToolPaths.ffmpeg()).thenReturn("ffmpeg");
	}

	private static TranscodeExecution ok() {
		return new TranscodeExecution(true, 0, "");
	}

	private static TranscodeExecution refused() {
		return new TranscodeExecution(true, 1, "OpenEncodeSessionEx failed: unsupported device");
	}

	@Test
	void keepsTheFirstEncoderThatOpensASession() throws Exception {
		when(transcodeRunner.run(argThat(command -> command != null && command.contains("hevc_nvenc")), any(), any()))
				.thenReturn(refused());
		when(transcodeRunner.run(argThat(command -> command != null && command.contains("hevc_qsv")), any(), any()))
				.thenReturn(ok());

		Assertions.assertThat(probe.hardwareEncoder()).contains(VideoEncoder.QUICK_SYNC);
		Assertions.assertThat(probe.isAvailable()).isTrue();
	}

	/**
	 * A listed encoder that refuses the session is exactly the case that made this
	 * class necessary, so the answer must be "no card" and not the first candidate.
	 */
	@Test
	void reportsNoEncoderWhenEveryCandidateRefuses() throws Exception {
		when(transcodeRunner.run(any(), any(), any())).thenReturn(refused());

		Assertions.assertThat(probe.hardwareEncoder()).isEmpty();
		Assertions.assertThat(probe.isAvailable()).isFalse();
	}

	@Test
	void treatsAProbeThatCannotRunAsNoEncoder() throws Exception {
		when(transcodeRunner.run(any(), any(), any())).thenThrow(new IOException("ffmpeg missing"));

		Assertions.assertThat(probe.hardwareEncoder()).isEmpty();
	}

	/**
	 * Hardware does not appear between two conversions, and the screen asks on
	 * every render: probing once keeps a process spawn off the request path.
	 */
	@Test
	void probesOnlyOnceAndReusesTheAnswer() throws Exception {
		when(transcodeRunner.run(any(), any(), any())).thenReturn(ok());

		probe.hardwareEncoder();
		probe.hardwareEncoder();
		probe.isAvailable();

		verify(transcodeRunner, times(1)).run(any(), any(), any());
	}

	@Test
	void probesWithAThrowawayEncodeRatherThanAskingWhatIsInstalled() throws Exception {
		when(transcodeRunner.run(any(), any(), any())).thenReturn(ok());

		probe.hardwareEncoder();

		verify(transcodeRunner).run(argThat((List<String> command) -> command != null
				&& command.getFirst().equals("ffmpeg") && command.contains("-f") && command.contains("null")), any(),
				any());
	}

	/**
	 * The probe hands ffmpeg a progress consumer like any other run, and an
	 * interrupted probe answers "no encoder" while restoring the interrupt flag
	 * instead of swallowing it.
	 */
	@SuppressWarnings("unchecked") @Test
	void ignoresProgressAndSurvivesAnInterruptedProbe() throws Exception {
		when(transcodeRunner.run(any(), any(), any())).thenAnswer(invocation -> {
			invocation.getArgument(1, Consumer.class).accept("out_time_us=1000");

			// A probe is never cancelled: it is a tenth of a second, and the runner has to
			// be told so, like it is for a real conversion.
			Assertions.assertThat(invocation.getArgument(2, BooleanSupplier.class).getAsBoolean()).isFalse();

			throw new InterruptedException("probe interrupted");
		});

		Assertions.assertThat(probe.hardwareEncoder()).isEmpty();
		Assertions.assertThat(Thread.interrupted()).isTrue();
	}

	@Test
	void triesTheCandidatesInTheDeclaredOrder() throws Exception {
		when(transcodeRunner.run(any(), any(), any())).thenReturn(refused());

		probe.hardwareEncoder();

		for (VideoEncoder candidate : VideoEncoder.hardwareCandidates()) {
			verify(transcodeRunner).run(argThat(command -> command != null && command.contains(candidate.ffmpegName())),
					any(), any());
		}

		Assertions.assertThat(VideoEncoder.hardwareCandidates()).first().isEqualTo(VideoEncoder.NVENC);
	}

	@Test
	void softwareIsNeverProbedBecauseItAlwaysWorks() {
		Assertions.assertThat(VideoEncoder.hardwareCandidates()).doesNotContain(VideoEncoder.SOFTWARE);
		Assertions.assertThat(VideoEncoder.SOFTWARE.hardware()).isFalse();
		Assertions.assertThat(Optional.of(VideoEncoder.NVENC).map(VideoEncoder::hardware)).contains(true);
	}
}