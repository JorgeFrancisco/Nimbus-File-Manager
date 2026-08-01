package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.TranscodeExecution;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.VideoEncoder;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolPaths;
import lombok.extern.slf4j.Slf4j;

/**
 * Whether this machine can really encode with the GPU. Asking ffmpeg which
 * encoders it was built with is not enough: a build routinely lists
 * {@code hevc_nvenc} on a laptop whose NVIDIA chip has no encoder block at all
 * (the whole MX line), and {@code hevc_amf} where there is no AMD card - the
 * failure only surfaces when a real encode session is opened. So the probe
 * encodes a few generated frames and believes the result.
 *
 * <p>
 * The answer is cached for the life of the application: hardware does not
 * appear between two conversions, and the probe costs a process spawn that the
 * screen would otherwise pay on every render.
 */
@Slf4j
@Component
public class HardwareEncoderProbe {

	private final ExternalToolPaths externalToolPaths;
	private final VideoTranscodeRunner transcodeRunner;

	private VideoEncoder encoder;
	private boolean probed;

	public HardwareEncoderProbe(ExternalToolPaths externalToolPaths, VideoTranscodeRunner transcodeRunner) {
		this.externalToolPaths = externalToolPaths;
		this.transcodeRunner = transcodeRunner;
	}

	/**
	 * The GPU encoder this machine can use, or empty when none can. Whichever card
	 * is installed answers for itself: nothing here assumes a vendor, so moving the
	 * installation to another machine changes the answer and nothing else.
	 */
	public synchronized Optional<VideoEncoder> hardwareEncoder() {
		if (probed) {
			return Optional.ofNullable(encoder);
		}

		encoder = VideoEncoder.hardwareCandidates().stream().filter(this::encodes).findFirst().orElse(null);
		probed = true;

		if (encoder == null) {
			log.info("No hardware video encoder available on this machine");
		} else {
			log.info("Hardware video encoder available: {}", encoder.ffmpegName());
		}

		return Optional.ofNullable(encoder);
	}

	public boolean isAvailable() {
		return hardwareEncoder().isPresent();
	}

	private boolean encodes(VideoEncoder candidate) {
		// A tenth of a second of generated video, encoded and thrown away: enough to
		// open a session on the GPU, short enough to cost nothing.
		List<String> command = List.of(externalToolPaths.ffmpeg(), "-hide_banner", "-loglevel", "error", "-f", "lavfi",
				"-i", "testsrc2=size=320x240:rate=30:duration=0.1", "-c:v", candidate.ffmpegName(), "-f", "null", "-");

		try {
			TranscodeExecution execution = transcodeRunner.run(command, _ -> {
			}, () -> false);

			return execution.successful();
		} catch (InterruptedException _) {
			Thread.currentThread().interrupt();

			return false;
		} catch (Exception e) {
			// An unreadable answer is a "no": offering an encoder that cannot open a
			// session would only fail later, in the middle of the user's batch.
			log.debug("Probe of {} failed", candidate.ffmpegName(), e);

			return false;
		}
	}
}