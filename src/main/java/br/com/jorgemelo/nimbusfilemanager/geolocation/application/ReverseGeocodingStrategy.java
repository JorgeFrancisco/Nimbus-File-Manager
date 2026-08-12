package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import java.util.Optional;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.Coordinates;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LocationResolution;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationProvider;

/**
 * Strategy for turning coordinates into a location. Implementations must be
 * pure resolvers: they never know about timeline, organization, screens or
 * interface DTOs. New providers (Google Maps, OpenStreetMap, Photon) are added
 * by implementing this interface and registering as a Spring bean -
 * nothing else in the application changes.
 */
public interface ReverseGeocodingStrategy {

	Optional<LocationResolution> resolve(Coordinates coordinates);

	LocationProvider provider();

	/** Whether the strategy can currently resolve (e.g. dataset imported). */
	boolean isAvailable();
}