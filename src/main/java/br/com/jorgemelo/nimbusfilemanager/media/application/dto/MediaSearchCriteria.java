package br.com.jorgemelo.nimbusfilemanager.media.application.dto;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.enums.GeoPresence;

/**
 * What {@code GET /api/media} accepts. It carries the same narrowing the
 * timeline panel offers, so that what can be seen on the screen can also be
 * asked for by a script - an API that filtered by less would be the reason
 * somebody scrapes the HTML.
 */
public record MediaSearchCriteria(FileType fileType, String codec, String folder, String extension, Integer year,
		Integer month, Long minSizeBytes, Long maxSizeBytes, String manufacturer, String model,
		Double minDurationSeconds, Double maxDurationSeconds, Integer minLongestSide, GeoPresence geo) {
}