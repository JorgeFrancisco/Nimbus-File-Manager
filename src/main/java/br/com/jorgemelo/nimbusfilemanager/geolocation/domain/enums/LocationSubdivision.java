package br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums;

/**
 * Geographic subdivision applied under the chosen organization layout, right
 * after the date segments (e.g. Year/Month/Brasil/Parana/Curitiba/CAMERA). The
 * label the screen shows lives in the message bundles, under
 * {@code enum.locationSubdivision.*}.
 */
public enum LocationSubdivision {

	NONE, COUNTRY, COUNTRY_STATE, COUNTRY_STATE_CITY
}