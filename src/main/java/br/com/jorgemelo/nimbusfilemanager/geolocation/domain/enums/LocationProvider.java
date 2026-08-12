package br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums;

/**
 * Source of a resolved media location. The rest of the application depends only
 * on this enum and on the geolocation abstractions - never on a concrete
 * provider implementation.
 */
public enum LocationProvider {

	ADMIN_BOUNDARIES, GOOGLE_MAPS, OPENSTREETMAP, UNKNOWN;
}