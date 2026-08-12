package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.constants.ConversionConstants;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionFailure;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.MediaInfoService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoMetadata;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ProcessingMetrics;
import lombok.extern.slf4j.Slf4j;

/**
 * Proves a converted file is worth keeping before anything irreversible happens
 * to the original. A zero exit code from ffmpeg is not enough on its own: the
 * output is probed again and must be a readable H.265 video of essentially the
 * same length as the source. Anything else is reported as a failure, which is
 * what keeps a truncated or silently re-muxed file from sending the original to
 * quarantine.
 */
@Slf4j
@Component
public class ConvertedVideoValidator {

	/**
	 * How far the converted duration may drift from the source. Container rounding
	 * and a re-written frame index routinely shift the reported duration by a few
	 * hundredths of a second, so a small absolute slack is combined with a
	 * proportional one for long videos.
	 */
	private static final double DURATION_TOLERANCE_SECONDS = 1.0;
	private static final double DURATION_TOLERANCE_RATIO = 0.02;

	private final MediaInfoService mediaInfoService;

	public ConvertedVideoValidator(MediaInfoService mediaInfoService) {
		this.mediaInfoService = mediaInfoService;
	}

	/**
	 * Empty when the converted file passed every check; otherwise the reason it
	 * cannot be trusted.
	 */
	public Optional<ConversionFailure> validate(Path converted, Double sourceDurationSeconds,
			ProcessingMetrics metrics) {
		if (!Files.isRegularFile(converted) || isEmpty(converted)) {
			return Optional.of(ConversionFailure.OUTPUT_MISSING);
		}

		VideoMetadata metadata;

		try {
			metadata = mediaInfoService.extract(converted, metrics);
		} catch (RuntimeException e) {
			log.warn("Could not probe the converted file {}", converted, e);

			return Optional.of(ConversionFailure.NOT_PROBEABLE);
		}

		if (!isHevc(metadata.videoCodec())) {
			return Optional.of(ConversionFailure.NOT_HEVC);
		}

		if (durationDrifted(sourceDurationSeconds, metadata.durationSeconds())) {
			return Optional.of(ConversionFailure.DURATION_MISMATCH);
		}

		return Optional.empty();
	}

	private boolean isEmpty(Path converted) {
		try {
			return Files.size(converted) == 0;
		} catch (IOException e) {
			log.warn("Could not read the size of the converted file {}", converted, e);

			return true;
		}
	}

	private boolean isHevc(String codec) {
		return codec != null && ConversionConstants.HEVC_CODECS.contains(codec.trim().toLowerCase(Locale.ROOT));
	}

	/**
	 * A source of unknown duration cannot be compared, so the check is skipped
	 * rather than turned into a false failure; the codec check above still applies.
	 */
	private boolean durationDrifted(Double sourceSeconds, Double convertedSeconds) {
		if (sourceSeconds == null || sourceSeconds <= 0) {
			return false;
		}

		if (convertedSeconds == null) {
			return true;
		}

		double tolerance = Math.max(DURATION_TOLERANCE_SECONDS, sourceSeconds * DURATION_TOLERANCE_RATIO);

		return Math.abs(sourceSeconds - convertedSeconds) > tolerance;
	}
}