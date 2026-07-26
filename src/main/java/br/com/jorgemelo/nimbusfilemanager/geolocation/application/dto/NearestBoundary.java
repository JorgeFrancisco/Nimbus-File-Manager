package br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto;

import org.locationtech.jts.geom.Geometry;

import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.GeoAdminBoundary;

/**
 * Closest boundary of one administrative level to a coordinate, with the parsed
 * geometry that produced the distance. Carries the geometry because the caller
 * needs its interior point to continue resolving the hierarchy on land, and
 * reparsing it would throw away the cache hit.
 */
public record NearestBoundary(GeoAdminBoundary boundary, Geometry geometry, double distanceKm) {
}