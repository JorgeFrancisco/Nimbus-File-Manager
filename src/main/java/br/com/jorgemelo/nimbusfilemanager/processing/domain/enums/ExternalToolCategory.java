package br.com.jorgemelo.nimbusfilemanager.processing.domain.enums;

/**
 * Kinds of rate-limited external process the app currently spawns. Each
 * category has its own independent concurrency limit in
 * {@code ExternalToolGate}, because their costs differ sharply and must not
 * share a single limit.
 *
 * <p>
 * Only the categories actually used today are declared — no dead entries. EXIF
 * is deliberately absent: it runs in-JVM via the {@code metadata-extractor}
 * library, not as an external process, so it is bounded by the worker pool
 * alone.
 */
public enum ExternalToolCategory {

	/**
	 * ffmpeg invoked to normalize one frame for a photo's 256-bit pHash and SSIM
	 * sample.
	 */
	FFMPEG_PHOTO_HASH,

	/**
	 * ffmpeg invoked once per video to extract and normalize several frames for the
	 * video's per-frame 256-bit pHash and SSIM samples. Kept separate from
	 * {@link #FFMPEG_PHOTO_HASH} because a multi-frame decode is heavier than a
	 * single-frame one and must be throttled on its own.
	 */
	FFMPEG_VIDEO_FRAME,

	/** ffprobe invoked to read a video's stream/format metadata. */
	FFPROBE_VIDEO,

	/**
	 * ffmpeg invoked to re-encode a whole video to H.265. By far the heaviest
	 * category - minutes of saturated CPU per file instead of a fraction of a
	 * second - so it gets its own, much smaller limit and cannot be starved by (or
	 * starve) the quick hash/probe work an inventory runs at the same time.
	 */
	FFMPEG_TRANSCODE
}