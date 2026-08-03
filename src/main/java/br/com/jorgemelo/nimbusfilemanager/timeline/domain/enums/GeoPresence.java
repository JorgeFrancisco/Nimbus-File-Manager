package br.com.jorgemelo.nimbusfilemanager.timeline.domain.enums;

/**
 * Whether the timeline is asking for media that knows where it was taken.
 *
 * <p>
 * {@link #WITHOUT_LOCATION} is the one that pays for this filter existing: an
 * old library is full of media that never carried GPS, and being able to list
 * exactly those is the first step to placing them on the map by hand.
 */
public enum GeoPresence {

	ANY, WITH_LOCATION, WITHOUT_LOCATION
}