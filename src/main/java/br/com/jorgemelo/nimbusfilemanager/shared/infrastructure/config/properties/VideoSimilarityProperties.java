package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the <b>comparison</b> stage of the video similarity algorithm
 * (prefix {@code nimbus-file-manager.duplicates.video-similarity}). Standalone
 * {@link ConfigurationProperties} so it never touches the
 * {@code NimbusFileManagerProperties} record's constructor.
 *
 * <p>
 * These only affect how already-computed fingerprints are compared (quorum,
 * trimmed mean, per-frame pHash radius, duration/aspect tolerance, candidate
 * cap), so changing them is cheap and never invalidates stored fingerprints -
 * the similarity cache simply recomputes. Parameters that affect the stored
 * fingerprint itself (how many frames are sampled) are <b>not</b> here: they
 * are part of the algorithm's identity (its {@code algorithm} string), because
 * changing them would silently break the {@code sampleIndex} alignment of
 * existing fingerprints. Every value is optional; an unset, out-of-range or
 * invalid value uses a documented fallback and logs a WARN rather than failing
 * startup.
 */
@ConfigurationProperties(prefix = "nimbus-file-manager.duplicates.video-similarity")
public record VideoSimilarityProperties(Integer minConcordantFrames, Integer trimmedLowestFrames,
		Integer maxFrameHashDistance, Double durationToleranceSeconds, Double aspectRatioTolerance) {

	private static final Logger log = LoggerFactory.getLogger(VideoSimilarityProperties.class);

	public static final int DEFAULT_MIN_CONCORDANT_FRAMES = 3;
	public static final int DEFAULT_TRIMMED_LOWEST_FRAMES = 1;
	/** Upper bound guarding against an absurd config value, not the frame count. */
	public static final int MAX_FRAME_QUORUM = 64;

	public static final int DEFAULT_MAX_FRAME_HASH_DISTANCE = 96;
	public static final int MIN_FRAME_HASH_DISTANCE = 0;
	public static final int MAX_FRAME_HASH_DISTANCE = 256;

	public static final double DEFAULT_DURATION_TOLERANCE_SECONDS = 3.0;
	public static final double DEFAULT_ASPECT_RATIO_TOLERANCE = 0.12;

	/**
	 * How many aligned frame pairs must reach the similarity threshold for two
	 * videos to be a match. The comparison also caps it at the number of frames the
	 * videos actually share, so a small value here is the effective quorum.
	 */
	public int minConcordantFramesOrDefault() {
		return normalizeInt("minConcordantFrames", minConcordantFrames, DEFAULT_MIN_CONCORDANT_FRAMES, 1,
				MAX_FRAME_QUORUM);
	}

	/**
	 * How many of the lowest per-frame scores are dropped before averaging (trimmed
	 * mean). The comparison caps it at {@code frames - 1}, so it never trims away
	 * every frame.
	 */
	public int trimmedLowestFramesOrDefault() {
		return normalizeInt("trimmedLowestFrames", trimmedLowestFrames, DEFAULT_TRIMMED_LOWEST_FRAMES, 0,
				MAX_FRAME_QUORUM);
	}

	public int maxFrameHashDistanceOrDefault() {
		return normalizeInt("maxFrameHashDistance", maxFrameHashDistance, DEFAULT_MAX_FRAME_HASH_DISTANCE,
				MIN_FRAME_HASH_DISTANCE, MAX_FRAME_HASH_DISTANCE);
	}

	public double durationToleranceSecondsOrDefault() {
		return normalizeDouble("durationToleranceSeconds", durationToleranceSeconds,
				DEFAULT_DURATION_TOLERANCE_SECONDS);
	}

	public double aspectRatioToleranceOrDefault() {
		return normalizeDouble("aspectRatioTolerance", aspectRatioTolerance, DEFAULT_ASPECT_RATIO_TOLERANCE);
	}

	private static int normalizeInt(String name, Integer value, int fallback, int min, int max) {
		if (value == null) {
			return fallback;
		}

		if (value < min) {
			log.warn("nimbus-file-manager.duplicates.video-similarity.{}={} is below the minimum {}; using {}.", name,
					value, min, min);

			return min;
		}

		if (value > max) {
			log.warn("nimbus-file-manager.duplicates.video-similarity.{}={} is above the maximum {}; using {}.", name,
					value, max, max);

			return max;
		}

		return value;
	}

	private static double normalizeDouble(String name, Double value, double fallback) {
		if (value == null) {
			return fallback;
		}

		if (value < 0) {
			log.warn("nimbus-file-manager.duplicates.video-similarity.{}={} is negative; using {}.", name, value,
					fallback);

			return fallback;
		}

		return value;
	}
}