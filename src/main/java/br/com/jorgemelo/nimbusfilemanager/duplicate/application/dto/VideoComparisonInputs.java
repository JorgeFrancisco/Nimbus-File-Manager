package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.MediaMetadata;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Video;

/**
 * Everything a video's approved relations were computed from that does
 * <em>not</em> live in its fingerprint.
 *
 * <p>
 * The comparison reads three things the frames do not carry: the duration, from
 * {@code video}, and the display width and height, from {@code media_metadata}.
 * The duration and aspect gates decide whether two videos are compared at all,
 * so a relation approved under one duration is a statement about a file that no
 * longer exists once the duration is read again - and nothing in the fingerprint
 * would show it.
 *
 * <p>
 * Grouped into one value so that "did the comparison's inputs change" is one
 * comparison rather than three, and so that a fourth input arriving later has
 * one place to be added rather than every caller to be revisited.
 */
public record VideoComparisonInputs(Double durationSeconds, Integer displayWidth, Integer displayHeight) {

	/**
	 * What the catalog currently holds, tolerating everything a half-built file
	 * legitimately misses - a video row that does not exist yet, metadata that has
	 * not been extracted. A missing value is a value, and changing from absent to
	 * present is exactly the change this exists to notice.
	 */
	public static VideoComparisonInputs of(CatalogFile catalogFile) {
		Video video = catalogFile == null ? null : catalogFile.getVideo();
		MediaMetadata metadata = catalogFile == null ? null : catalogFile.getMetadata();

		return new VideoComparisonInputs(video == null ? null : video.getDurationSeconds(),
				metadata == null ? null : metadata.getDisplayWidth(),
				metadata == null ? null : metadata.getDisplayHeight());
	}
}