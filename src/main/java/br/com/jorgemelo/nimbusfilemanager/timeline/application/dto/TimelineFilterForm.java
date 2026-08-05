package br.com.jorgemelo.nimbusfilemanager.timeline.application.dto;

import java.time.LocalDate;

import br.com.jorgemelo.nimbusfilemanager.timeline.domain.enums.GeoPresence;

/**
 * The filter panel as it arrives from a request, one field per control.
 *
 * <p>
 * Flat rather than grouped because this is what a query string and an HTML form
 * can express - {@code ?capturedFrom=2008-01-01&minLongestSide=1920}. The window
 * is not called {@code from}: the timeline already takes a {@code from} to jump
 * to a month, and binding both from the same query string made clicking a month
 * ask for its first day and nothing else. The grouped value
 * the rest of the application works with is built by {@code TimelineFilters},
 * so the shape of the URL never dictates the shape of the domain.
 *
 * <p>
 * Sizes arrive in megabytes and durations in seconds, because those are the
 * units the person on the screen thinks in; the conversion to bytes happens on
 * the way in, once.
 */
public record TimelineFilterForm(LocalDate capturedFrom, LocalDate capturedTo, String manufacturer, String model,
		Long minSizeMb,
		Long maxSizeMb, Double minDurationSeconds, Double maxDurationSeconds, Integer minLongestSide,
		GeoPresence geo) {
}