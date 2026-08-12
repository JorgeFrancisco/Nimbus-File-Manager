package br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants;

/**
 * Algorithm identifiers for visual fingerprints. The string fully identifies
 * hash <b>compatibility</b>: decoder + scaler + grayscale + hash + version.
 * Fingerprints with different identifiers are never compared to each other, so
 * changing any stage of the pipeline (e.g. moving photo decoding from
 * ffmpeg/lanczos to an in-JVM scaler) MUST be a new identifier - never a silent
 * change of an existing one.
 */
public final class FingerprintAlgorithm {

	/** Current photo fingerprint: 32x32 luminance sample and 256-bit DCT pHash. */
	public static final String FFMPEG_LANCZOS_PHASH_256_V1 = "FFMPEG_LANCZOS_PHASH_256_V1";

	/**
	 * Superseded video fingerprint: the frames were reached by decoding the file
	 * sequentially and selecting the ones crossing each timestamp. Nothing produces
	 * these any more - {@link #FFMPEG_LANCZOS_PHASH_256_FRAMES_V2} replaced it -
	 * and the identifier stays because the rows do: they remain a family of their
	 * own, never compared against V2, until a rebuild replaces them.
	 */
	public static final String FFMPEG_LANCZOS_PHASH_256_FRAMES_V1 = "FFMPEG_LANCZOS_PHASH_256_FRAMES_V1";

	/**
	 * Current video fingerprint: five frames at deterministic relative positions,
	 * each reached by an input seek and normalized to the same 32x32 luminance
	 * sample and 256-bit DCT pHash as the photo algorithm. One
	 * {@code media_fingerprint} row per frame (sample_index = relative position,
	 * position_ms = sampled timestamp).
	 *
	 * <p>
	 * A new identifier rather than a faster V1, and the reason is measured: over
	 * 267 frames of real videos, seeking reproduced the sequential decode's frame
	 * byte for byte 265 times. Twice it did not - a seek lands on the frame at the
	 * timestamp, and where the timestamps are irregular that need not be the frame
	 * a sequential pass crossed. Two in 267 is rare enough to be invisible in
	 * testing and far too common to call the same algorithm, which is exactly the
	 * case this class exists to name.
	 */
	public static final String FFMPEG_LANCZOS_PHASH_256_FRAMES_V2 = "FFMPEG_LANCZOS_PHASH_256_FRAMES_V2";

	private FingerprintAlgorithm() {
	}
}