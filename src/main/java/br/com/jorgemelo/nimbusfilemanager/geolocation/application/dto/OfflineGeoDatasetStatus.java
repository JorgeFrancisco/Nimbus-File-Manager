package br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto;

import java.time.LocalDateTime;

/**
 * Snapshot of the offline geographic dataset for the admin screen:
 * availability, provider label, license/attribution, version, size on disk,
 * imported record/polygon count and when it was imported. Technology-neutral: it
 * never names a concrete source.
 *
 * <p>
 * Two things it deliberately no longer carries. There is no "downloaded at",
 * because the field never held one: it was stamped when the run started, before
 * anything was fetched, and a run that transferred no bytes stamped it anyway -
 * so the screen labelled a moment of verification as a moment of download. What
 * the screen wants there is when the dataset was last checked against its
 * source, and the execution that did the checking is what knows it. And there is
 * no last error, because a failure belongs to the run that failed; the settings
 * page reads it from there already.
 *
 * @param importedAt when the boundaries now installed were imported - written by
 * the transaction that wrote them, so it always describes the rows that are
 * actually there
 */
public record OfflineGeoDatasetStatus(boolean available, String version, long importedRecords, long sizeBytes,
		LocalDateTime importedAt, String directory, String provider, String license) {

	public static OfflineGeoDatasetStatus unavailable(String directory) {
		return new OfflineGeoDatasetStatus(false, null, 0, 0, null, directory, null, null);
	}
}