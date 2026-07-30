package br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums;

/**
 * What organization does when a media has no resolved location or its
 * confidence is below the configured minimum. The label the screen shows lives
 * in the message bundles, under {@code enum.locationFallback.*}.
 */
public enum LocationFallbackMode {

	IGNORE, FALLBACK_FOLDER
}