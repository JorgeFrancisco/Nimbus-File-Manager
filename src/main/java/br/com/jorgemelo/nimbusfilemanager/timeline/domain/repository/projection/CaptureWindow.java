package br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection;

import java.time.LocalDate;

/**
 * The stretch of time the timeline is looking at, either end open.
 *
 * @param from the first day to include, or {@code null} for "since the
 * beginning"
 * @param to the last day to include, or {@code null} for "up to the most
 * recent"
 */
public record CaptureWindow(LocalDate from, LocalDate to) {

	public static final CaptureWindow ANY = new CaptureWindow(null, null);

	public boolean isAny() {
		return from == null && to == null;
	}
}