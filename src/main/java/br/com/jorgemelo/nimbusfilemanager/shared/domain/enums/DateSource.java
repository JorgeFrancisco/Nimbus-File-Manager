package br.com.jorgemelo.nimbusfilemanager.shared.domain.enums;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Where a media file's capture date came from. Doubles as a confidence signal
 * (higher trust first), so downstream logic (e.g. the duplicate recommender)
 * can weight dates by reliability instead of comparing raw values:
 *
 * <ul>
 * <li>{@code EXIF}/{@code MEDIA_INFO} - embedded in the content, the true
 * capture instant.</li>
 * <li>{@code FILE_NAME_CONFIRMED} - the day came from the file name AND a
 * filesystem timestamp (modified/created) of the same day corroborated it, so
 * we adopted that timestamp for the real time-of-day. High trust.</li>
 * <li>{@code FILE_NAME}/{@code FOLDER_LAYOUT} - day parsed from the
 * name/folder, not corroborated. Medium trust when it is a bare day (midnight);
 * a name that carries the time of day is worth as much as a corroborated one -
 * see {@link #trustOf(DateSource, LocalDateTime)}.</li>
 * <li>{@code FILE_CREATED_AT}/{@code FILE_MODIFIED_AT} - filesystem timestamp
 * with nothing to corroborate it (a copy/sync date). Low trust.</li>
 * <li>{@code UNKNOWN} - none available.</li>
 * </ul>
 */
public enum DateSource {

	EXIF, MEDIA_INFO, FILE_NAME, FILE_NAME_CONFIRMED, FILE_CREATED_AT, FILE_MODIFIED_AT, FOLDER_LAYOUT, UNKNOWN;

	/**
	 * The confidence tier of this source, so callers compare trust instead of
	 * re-listing the order above: the duplicate recommender picks which copy keeps
	 * its date, and a conversion keeps the original's date when the re-extracted
	 * one is weaker.
	 */
	public int trust() {
		return switch (this) {
		case EXIF, MEDIA_INFO -> 5;
		case FILE_NAME_CONFIRMED -> 4;
		case FILE_NAME, FOLDER_LAYOUT -> 3;
		case FILE_MODIFIED_AT -> 2;
		case FILE_CREATED_AT -> 1;
		case UNKNOWN -> 0;
		};
	}

	/** Trust of a possibly absent source; an absent one trusts nothing. */
	public static int trustOf(DateSource source) {
		return source == null ? 0 : source.trust();
	}

	/**
	 * Trust of a date together with where it came from. A name-derived date that
	 * already carries a time of day is worth as much as one a filesystem timestamp
	 * corroborated: the time was written by whatever produced the file, which is at
	 * least as reliable as an mtime that merely happened to fall on the same day -
	 * and it is the reason the refiner leaves such a date alone. A bare day
	 * (midnight) stays below it, because that is what corroboration adds.
	 */
	public static int trustOf(DateSource source, LocalDateTime captureDate) {
		if (source == FILE_NAME && captureDate != null && !LocalTime.MIDNIGHT.equals(captureDate.toLocalTime())) {
			return FILE_NAME_CONFIRMED.trust();
		}

		return trustOf(source);
	}
}