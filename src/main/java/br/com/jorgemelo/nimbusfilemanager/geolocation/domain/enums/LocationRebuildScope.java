package br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums;

/**
 * Scope of a location rebuild over already-inventoried media.
 */
public enum LocationRebuildScope {

	/** Media with GPS and no resolved location yet. */
	PENDING,

	/** Media whose automatic resolution has LOW or VERY_LOW confidence. */
	LOW_CONFIDENCE,

	/** Every media with GPS. */
	ALL
}