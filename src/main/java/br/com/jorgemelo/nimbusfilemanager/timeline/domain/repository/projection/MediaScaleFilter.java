package br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection;

/**
 * How big the media is, on the three scales the catalog already records: bytes
 * on disk, seconds of video, and pixels on the long side.
 *
 * <p>
 * They travel together because they answer the same kind of question - "the
 * heavy ones", "the long ones", "the ones worth printing" - and because a
 * filter with a parameter for each end of each scale would be six arguments on
 * its own.
 *
 * @param minBytes smallest file size to include, or {@code null} for no floor
 * @param maxBytes largest file size to include, or {@code null} for no ceiling
 * @param minDurationSeconds shortest video to include; photos have no duration
 * and are excluded by any floor above zero
 * @param maxDurationSeconds longest video to include, or {@code null}
 * @param minLongestSide smallest long side in pixels, which is how "at least
 * Full HD" is asked without caring about orientation
 */
public record MediaScaleFilter(Long minBytes, Long maxBytes, Double minDurationSeconds, Double maxDurationSeconds,
		Integer minLongestSide) {

	public static final MediaScaleFilter ANY = new MediaScaleFilter(null, null, null, null, null);
}