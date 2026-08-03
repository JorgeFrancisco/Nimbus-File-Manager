package br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection;

import br.com.jorgemelo.nimbusfilemanager.timeline.domain.enums.GeoPresence;

/**
 * What the timeline is being asked to narrow by, beyond the media type and the
 * subcategory it has always had.
 *
 * <p>
 * Grouped into one value rather than passed loose because the list is already
 * nine fields and each query would otherwise grow that many parameters. Each
 * group is a record of its own, so no constructor here reaches the
 * seven-parameter limit and a caller that does not offer a whole group hands
 * over its {@code ANY}.
 *
 * <p>
 * Every field is optional by design: {@link #NONE} narrows nothing and has to
 * produce exactly the timeline that existed before any of this, which is what
 * makes adding the panel safe for whoever never opens it.
 */
public record TimelineFilter(CaptureWindow window, CameraFilter camera, MediaScaleFilter scale, GeoPresence geo) {

	public static final TimelineFilter NONE = new TimelineFilter(CaptureWindow.ANY, CameraFilter.ANY,
			MediaScaleFilter.ANY, GeoPresence.ANY);

	/**
	 * Whether anything at all is being narrowed. The screen uses it to decide
	 * whether to show the "filters are on" state, so that a filtered timeline
	 * never looks like an empty library.
	 */
	public boolean isNarrowing() {
		return !window.isAny() || camera.manufacturer() != null || camera.model() != null
				|| !MediaScaleFilter.ANY.equals(scale) || geo != GeoPresence.ANY;
	}
}