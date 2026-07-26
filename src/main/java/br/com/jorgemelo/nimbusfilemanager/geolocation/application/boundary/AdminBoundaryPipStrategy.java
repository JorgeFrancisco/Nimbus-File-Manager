package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.LocationConfidencePolicy;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.ReverseGeocodingStrategy;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.Coordinates;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.LocationResolution;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.Match;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.NearestMatch;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationProvider;

/**
 * Fully offline reverse geocoding by administrative containment
 * (Point-in-Polygon): finds the deepest administrative unit whose polygon
 * contains the coordinate. Correct by construction on metropolitan borders - it
 * never picks a neighbour by centroid distance. Only when the point is outside
 * every polygon (at sea near the coast, over water in flight) does it fall back
 * to the nearest boundary within {@link #NEAREST_FALLBACK_MAX_KM}, marked with
 * LOW confidence and the measured distance; farther out it answers open sea.
 * Pure resolver: it knows nothing about timeline, organization or screens.
 */
@Component
public class AdminBoundaryPipStrategy implements ReverseGeocodingStrategy {

	/**
	 * Tolerance for the nearest-boundary fallback: 12 nautical miles, the breadth of
	 * the territorial sea under the UN Convention on the Law of the Sea (in Brazil,
	 * Lei 8.617/1993). Water inside it is national territory, so approximating a
	 * coordinate there to the coast it belongs to is defensible; beyond it the point
	 * is genuinely unresolvable, not coastal GPS noise or a boat ride within sight
	 * of the shore. The administrative polygons themselves stop at the shoreline -
	 * no state or municipality has a maritime limit of its own - which is why the
	 * tolerance exists at all.
	 */
	static final double NEAREST_FALLBACK_MAX_KM = 22.2;

	private final AdminBoundaryResolver resolver;
	private final BoundaryGeometryCache geometryCache;
	private final LocationConfidencePolicy confidencePolicy;
	private final Clock clock;

	public AdminBoundaryPipStrategy(AdminBoundaryResolver resolver, BoundaryGeometryCache geometryCache,
			LocationConfidencePolicy confidencePolicy, Clock clock) {
		this.resolver = resolver;
		this.geometryCache = geometryCache;
		this.confidencePolicy = confidencePolicy;
		this.clock = clock;
	}

	@Override
	public Optional<LocationResolution> resolve(Coordinates coordinates) {
		if (coordinates == null) {
			return Optional.empty();
		}

		Optional<LocationResolution> contained = resolver.resolve(coordinates.latitude(), coordinates.longitude())
				.map(this::toResolution);

		if (contained.isPresent()) {
			return contained;
		}

		Optional<LocationResolution> approximate = resolver
				.resolveNearest(coordinates.latitude(), coordinates.longitude(), NEAREST_FALLBACK_MAX_KM)
				.map(this::toApproximateResolution);

		if (approximate.isPresent()) {
			return approximate;
		}

		// Nothing contains the point and nothing is within reach of it: this is open
		// water, and saying so is more useful than leaving the coordinate unresolved
		// forever. Recorded as a flag, never as a place name.
		return Optional.of(LocationResolution.openSea(LocationProvider.ADMIN_BOUNDARIES, LocalDateTime.now(clock)));
	}

	@Override
	public LocationProvider provider() {
		return LocationProvider.ADMIN_BOUNDARIES;
	}

	@Override
	public boolean isAvailable() {
		return geometryCache.available();
	}

	private LocationResolution toResolution(Match match) {
		// distanceKm has no meaning under Point-in-Polygon: the point is inside
		// (or outside) a polygon, not at a distance from a centroid.
		return new LocationResolution(match.countryCode(), match.countryName(), match.stateName(), match.cityName(),
				null, confidencePolicy.confidenceForKind(match.kind()), LocationProvider.ADMIN_BOUNDARIES, null,
				LocalDateTime.now(clock), false);
	}

	private LocationResolution toApproximateResolution(NearestMatch nearest) {
		// Here distanceKm is meaningful again: how far the point sits from the
		// municipality it was approximated to.
		Match match = nearest.match();

		return new LocationResolution(match.countryCode(), match.countryName(), match.stateName(), match.cityName(),
				nearest.distanceKm(), confidencePolicy.confidenceForNearestFallback(),
				LocationProvider.ADMIN_BOUNDARIES, null, LocalDateTime.now(clock), false);
	}
}