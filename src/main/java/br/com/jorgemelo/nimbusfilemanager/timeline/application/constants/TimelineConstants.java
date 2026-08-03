package br.com.jorgemelo.nimbusfilemanager.timeline.application.constants;

/**
 * Contract data constants for the timeline domain: the preferences page and key
 * so the media-type filter of the Timeline screen is remembered per user across
 * visits.
 */
public final class TimelineConstants {

	public static final String TIMELINE_PAGE_KEY = "timeline";
	public static final String TYPE_KEY = "type";

	/**
	 * Prefix of the filter-panel preferences. One key per control rather than a
	 * serialised blob: the preference store is key-value, and a blob would have to
	 * be parsed - and migrated - every time a control is added.
	 */
	public static final String FILTER_KEY_PREFIX = "filter.";

	private TimelineConstants() {
	}
}