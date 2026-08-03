package br.com.jorgemelo.nimbusfilemanager.timeline.application;

import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineFilterForm;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.enums.GeoPresence;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection.CameraFilter;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection.CaptureWindow;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection.MediaScaleFilter;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection.TimelineFilter;

/**
 * Turns the flat request form into the grouped filter the queries take.
 *
 * <p>
 * It lives here rather than on the form because a DTO in this project carries
 * no behaviour, and rather than in each controller because both the REST
 * endpoint and the screen need the same translation - including the unit
 * conversion, which is the part that would eventually differ between two copies
 * and produce a screen that filters by a different megabyte than the API.
 */
public final class TimelineFilters {

	private static final long BYTES_PER_MEGABYTE = 1024L * 1024L;

	private TimelineFilters() {
	}

	/**
	 * @param form the request form, or {@code null} when the caller offers no
	 * filter panel at all
	 * @return the grouped filter, never {@code null}
	 */
	public static TimelineFilter from(TimelineFilterForm form) {
		if (form == null) {
			return TimelineFilter.NONE;
		}

		return new TimelineFilter(new CaptureWindow(form.from(), form.to()),
				new CameraFilter(form.manufacturer(), form.model()),
				new MediaScaleFilter(toBytes(form.minSizeMb()), toBytes(form.maxSizeMb()), form.minDurationSeconds(),
						form.maxDurationSeconds(), form.minLongestSide()),
				form.geo() == null ? GeoPresence.ANY : form.geo());
	}

	private static Long toBytes(Long megabytes) {
		return megabytes == null ? null : megabytes * BYTES_PER_MEGABYTE;
	}
}