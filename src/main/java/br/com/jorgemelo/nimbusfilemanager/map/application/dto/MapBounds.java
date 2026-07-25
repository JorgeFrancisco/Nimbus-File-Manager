package br.com.jorgemelo.nimbusfilemanager.map.application.dto;

/**
 * The visible bounding box of the map, used to load only the pins in view so
 * the payload and marker count stay proportional to the viewport instead of the
 * whole library.
 */
public record MapBounds(double minLat, double minLon, double maxLat, double maxLon) {
}