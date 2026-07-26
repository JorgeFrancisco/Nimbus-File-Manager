package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import java.util.List;
import java.util.Optional;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.Match;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.NearestBoundary;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.NearestMatch;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.AdminBoundaryKind;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.GeoAdminBoundary;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.GeoAdminBoundaryRepository;

/**
 * Resolves a coordinate to its deepest administrative container by
 * Point-in-Polygon. Memory-safe for a worldwide dataset: it never loads the
 * globe into RAM. Each level is prefiltered in SQL by bounding box (the
 * (min_lat, max_lat, min_lon, max_lon) index) so only a handful of candidate
 * polygons are read, then confirmed with JTS {@code contains}. Parsed polygons
 * are reused through {@link BoundaryGeometryCache}, so the many coordinates
 * that fall in the same region parse each polygon only once. No PostGIS.
 *
 * <p>
 * Resolution is most-specific-first: municipality worldwide, then state, then
 * country, each carrying its own denormalized country columns. This keeps the
 * result correct even when ADM0 polygons overlap or disagree with deeper levels
 * (e.g. a source tagging an overseas territory under its sovereign state). When
 * polygons of the same level overlap, the smallest one wins.
 */
@Component
public class AdminBoundaryResolver {

	private static final double KM_PER_DEGREE_LAT = 111.32;
	private static final double EARTH_RADIUS_KM = 6371.0;

	private final GeoAdminBoundaryRepository repository;
	private final BoundaryGeometryCache geometryCache;
	private final GeometryFactory geometryFactory = new GeometryFactory();

	public AdminBoundaryResolver(GeoAdminBoundaryRepository repository, BoundaryGeometryCache geometryCache) {
		this.repository = repository;
		this.geometryCache = geometryCache;
	}

	@Transactional(readOnly = true)
	public Optional<Match> resolve(double latitude, double longitude) {
		Point point = geometryFactory.createPoint(new Coordinate(longitude, latitude));

		// Fast path: country first (only ~200 candidate polygons), then state
		// and municipality within it through the selective (country_code, kind)
		// index. This is the cost profile for virtually every coordinate.
		Optional<GeoAdminBoundary> country = bestContaining(
				repository.findCandidates(AdminBoundaryKind.COUNTRY, latitude, longitude), point);

		if (country.isPresent()) {
			String countryCode = country.get().getCountryCode();

			Optional<GeoAdminBoundary> municipality = bestContaining(repository
					.findCandidatesInCountry(AdminBoundaryKind.MUNICIPALITY, countryCode, latitude, longitude), point);
			Optional<GeoAdminBoundary> state = bestContaining(
					repository.findCandidatesInCountry(AdminBoundaryKind.STATE, countryCode, latitude, longitude),
					point);

			if (municipality.isPresent() || state.isPresent()) {
				AdminBoundaryKind kind = municipality.isPresent() ? AdminBoundaryKind.MUNICIPALITY
						: AdminBoundaryKind.STATE;

				return Optional
						.of(new Match(kind, municipality.map(GeoAdminBoundary::getName).orElse(null), countryCode,
								country.get().getCountryName(), state.map(GeoAdminBoundary::getName).orElse(null)));
			}
		}

		// Slow path, rare: a containing country without any of its own
		// subdivisions containing the point - e.g. a sovereign state's ADM0
		// drawn over a territory whose subdivisions carry another country code
		// (the Kingdom of the Netherlands over Aruba) - or no containing
		// country at all. Searches the deeper levels worldwide; their rows
		// carry the authoritative denormalized country.
		Optional<GeoAdminBoundary> municipality = bestContaining(
				repository.findCandidates(AdminBoundaryKind.MUNICIPALITY, latitude, longitude), point);

		if (municipality.isPresent()) {
			String countryCode = municipality.get().getCountryCode();

			Optional<GeoAdminBoundary> state = bestContaining(
					repository.findCandidatesInCountry(AdminBoundaryKind.STATE, countryCode, latitude, longitude),
					point);

			return Optional.of(new Match(AdminBoundaryKind.MUNICIPALITY, municipality.get().getName(), countryCode,
					municipality.get().getCountryName(), state.map(GeoAdminBoundary::getName).orElse(null)));
		}

		Optional<GeoAdminBoundary> state = bestContaining(
				repository.findCandidates(AdminBoundaryKind.STATE, latitude, longitude), point);

		if (state.isPresent()) {
			return Optional.of(new Match(AdminBoundaryKind.STATE, null, state.get().getCountryCode(),
					state.get().getCountryName(), state.get().getName()));
		}

		return country
				.map(c -> new Match(AdminBoundaryKind.COUNTRY, null, c.getCountryCode(), c.getCountryName(), null));
	}

	/**
	 * Fallback for coordinates outside every polygon (at sea near the coast, over
	 * water in flight, coastal GPS noise): finds the nearest municipality within
	 * {@code maxKm} and resolves the rest of the hierarchy from it. The state is
	 * confirmed by containment of the municipality's interior point; the country
	 * comes denormalized from the boundary row itself. Where no municipality exists
	 * to approximate to, the nearest country answers instead.
	 */
	@Transactional(readOnly = true)
	public Optional<NearestMatch> resolveNearest(double latitude, double longitude, double maxKm) {
		Optional<NearestBoundary> municipality = closest(AdminBoundaryKind.MUNICIPALITY, latitude, longitude, maxKm);

		if (municipality.isPresent()) {
			return municipality.map(this::toMunicipalityMatch);
		}

		// Some small states arrive with only a country outline and no subdivisions at
		// all. With no municipality to approximate to, a photo taken at the waterline
		// of such a country would stay unresolved even though its own polygon is metres
		// away. Approximating to the country is weak but true, and rates LOW like every
		// other approximation.
		return closest(AdminBoundaryKind.COUNTRY, latitude, longitude, maxKm)
				.map(nearest -> new NearestMatch(new Match(AdminBoundaryKind.COUNTRY, null,
						nearest.boundary().getCountryCode(), nearest.boundary().getCountryName(), null),
						nearest.distanceKm()));
	}

	private NearestMatch toMunicipalityMatch(NearestBoundary nearest) {
		GeoAdminBoundary best = nearest.boundary();

		// The interior point is guaranteed inside the municipality, hence on
		// land: containment of the state is checked there, not at sea.
		Point interior = nearest.geometry().getInteriorPoint();

		Optional<GeoAdminBoundary> state = bestContaining(repository.findCandidatesInCountry(AdminBoundaryKind.STATE,
				best.getCountryCode(), interior.getY(), interior.getX()), interior);

		return new NearestMatch(new Match(AdminBoundaryKind.MUNICIPALITY, best.getName(), best.getCountryCode(),
				best.getCountryName(), state.map(GeoAdminBoundary::getName).orElse(null)), nearest.distanceKm());
	}

	/**
	 * Closest boundary of one level within {@code maxKm}, measured to the polygon
	 * edge rather than to a centroid, so a wide region does not lose to a small
	 * neighbour just by being wide.
	 */
	private Optional<NearestBoundary> closest(AdminBoundaryKind kind, double latitude, double longitude,
			double maxKm) {
		double latMargin = maxKm / KM_PER_DEGREE_LAT;
		double lonMargin = maxKm / (KM_PER_DEGREE_LAT * Math.max(0.01, Math.cos(Math.toRadians(latitude))));

		Point point = geometryFactory.createPoint(new Coordinate(longitude, latitude));

		NearestBoundary best = null;

		double bestKm = maxKm;

		for (GeoAdminBoundary candidate : repository.findCandidatesNear(kind, latitude, longitude, latMargin,
				lonMargin)) {
			PreparedGeometry prepared = geometryCache.geometry(candidate);

			if (prepared == null) {
				continue;
			}

			Geometry geometry = prepared.getGeometry();

			Coordinate[] nearest = DistanceOp.nearestPoints(geometry, point);

			double km = haversineKm(latitude, longitude, nearest[0].y, nearest[0].x);

			if (km <= bestKm) {
				best = new NearestBoundary(candidate, geometry, km);
				bestKm = km;
			}
		}

		return Optional.ofNullable(best);
	}

	/**
	 * Among the bbox candidates whose polygon actually contains the point, picks
	 * the smallest by area: when same-level polygons overlap, the most specific one
	 * wins (e.g. a territory drawn inside a sovereign state's polygon).
	 */
	private Optional<GeoAdminBoundary> bestContaining(List<GeoAdminBoundary> candidates, Point point) {
		GeoAdminBoundary best = null;

		double bestArea = Double.MAX_VALUE;

		for (GeoAdminBoundary candidate : candidates) {
			PreparedGeometry geometry = geometryCache.geometry(candidate);

			if (geometry == null || !geometry.contains(point)) {
				continue;
			}

			double area = geometry.getGeometry().getArea();

			if (area < bestArea) {
				best = candidate;
				bestArea = area;
			}
		}

		return Optional.ofNullable(best);
	}

	private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
		double dLat = Math.toRadians(lat2 - lat1);
		double dLon = Math.toRadians(lon2 - lon1);

		double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
				* Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);

		return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
	}
}