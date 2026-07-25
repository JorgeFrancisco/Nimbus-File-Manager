package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the central processing coordination infrastructure
 * ({@code br.com.jorgemelo.nimbusfilemanager.processing}). Standalone
 * {@link ConfigurationProperties} (prefix
 * {@code nimbus-file-manager.processing}) so it never touches the
 * {@link NimbusFileManagerProperties} record's constructor.
 *
 * <p>
 * All values are optional; an unset, out-of-range or otherwise invalid value
 * uses a <b>documented fallback</b> (the default below, or the nearest bound)
 * and logs a WARN, rather than failing startup. External-process limits are
 * intentionally per-tool so ffmpeg-for-photo-hash and ffprobe-for-video can be
 * throttled independently; a future heavy category (e.g. H.265 transcode) would
 * add its own limit here without disturbing these.
 */
@ConfigurationProperties(prefix = "nimbus-file-manager.processing")
public record ProcessingProperties(Integer workers, Integer queueCapacity, Integer ffmpegPhotoHashLimit,
		Integer ffmpegVideoFrameLimit, Integer ffprobeVideoLimit) {

	private static final Logger log = LoggerFactory.getLogger(ProcessingProperties.class);

	public static final int DEFAULT_WORKERS = 4;
	public static final int MIN_WORKERS = 1;
	public static final int MAX_WORKERS = 64;

	public static final int DEFAULT_QUEUE_CAPACITY = 64;
	public static final int MIN_QUEUE_CAPACITY = 1;
	public static final int MAX_QUEUE_CAPACITY = 100_000;

	public static final int DEFAULT_FFMPEG_PHOTO_HASH_LIMIT = 4;
	public static final int DEFAULT_FFMPEG_VIDEO_FRAME_LIMIT = 4;
	public static final int DEFAULT_FFPROBE_VIDEO_LIMIT = 2;
	public static final int MIN_EXTERNAL_LIMIT = 1;
	public static final int MAX_EXTERNAL_LIMIT = 32;

	public int workersOrDefault() {
		return normalize("workers", workers, DEFAULT_WORKERS, MIN_WORKERS, MAX_WORKERS);
	}

	public int queueCapacityOrDefault() {
		return normalize("queueCapacity", queueCapacity, DEFAULT_QUEUE_CAPACITY, MIN_QUEUE_CAPACITY,
				MAX_QUEUE_CAPACITY);
	}

	public int ffmpegPhotoHashLimitOrDefault() {
		return normalize("ffmpegPhotoHashLimit", ffmpegPhotoHashLimit, DEFAULT_FFMPEG_PHOTO_HASH_LIMIT,
				MIN_EXTERNAL_LIMIT, MAX_EXTERNAL_LIMIT);
	}

	public int ffmpegVideoFrameLimitOrDefault() {
		return normalize("ffmpegVideoFrameLimit", ffmpegVideoFrameLimit, DEFAULT_FFMPEG_VIDEO_FRAME_LIMIT,
				MIN_EXTERNAL_LIMIT, MAX_EXTERNAL_LIMIT);
	}

	public int ffprobeVideoLimitOrDefault() {
		return normalize("ffprobeVideoLimit", ffprobeVideoLimit, DEFAULT_FFPROBE_VIDEO_LIMIT, MIN_EXTERNAL_LIMIT,
				MAX_EXTERNAL_LIMIT);
	}

	private static int normalize(String name, Integer value, int fallback, int min, int max) {
		if (value == null) {
			return fallback;
		}

		if (value < min) {
			log.warn("nimbus-file-manager.processing.{}={} is below the minimum {}; using {}.", name, value, min, min);

			return min;
		}

		if (value > max) {
			log.warn("nimbus-file-manager.processing.{}={} is above the maximum {}; using {}.", name, value, max, max);

			return max;
		}

		return value;
	}
}