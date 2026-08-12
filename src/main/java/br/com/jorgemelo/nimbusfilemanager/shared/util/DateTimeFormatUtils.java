package br.com.jorgemelo.nimbusfilemanager.shared.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.ClockHolder;

/**
 * Human-friendly rendering of {@link LocalDateTime} for the UI:
 * {@code dd/MM/yyyy HH:mm:ss.SSS}, <b>always</b> with the three-digit
 * millisecond suffix (so a value without sub-second precision shows
 * {@code .000}) for a consistent column. Called from Thymeleaf via
 * {@code T(...DateTimeFormatUtils).human(value)} so a raw ISO string (e.g.
 * {@code 2021-03-25T20:40:50}) never reaches a screen. {@code null} renders as
 * an em dash.
 */
public final class DateTimeFormatUtils {

	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss.SSS");
	private static final String EMPTY = "—";

	private DateTimeFormatUtils() {
	}

	/** What a screen shows where there is no date to show at all. */
	public static String absent() {
		return EMPTY;
	}

	public static String human(LocalDateTime value) {
		return value == null ? EMPTY : value.format(FORMATTER);
	}

	/**
	 * The same rendering for a moment rather than a reading.
	 *
	 * <p>
	 * This is where an instant becomes a wall clock, and the only place it may:
	 * the catalog holds when a file was written to as a point on the timeline, and
	 * a person reading a screen wants it in the time zone the application was told
	 * to work in. That zone comes from the application clock - the one the settings
	 * screen configures - so two screens never disagree and nothing here invents a
	 * zone of its own.
	 */
	public static String human(Instant value) {
		return value == null ? EMPTY : human(LocalDateTime.ofInstant(value, ClockHolder.clock().getZone()));
	}
}