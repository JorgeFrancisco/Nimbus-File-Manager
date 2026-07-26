package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.io.WKBReader;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.GeoAdminBoundary;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.GeoAdminBoundaryRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.LocationRebuildProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * In-memory acceleration layer over geo_admin_boundary shared by all reverse
 * geocoding. It (a) parses each polygon's WKB at most once and reuses the
 * prepared JTS geometry across the many coordinates that fall in the same
 * region - a {@link PreparedGeometry} is both faster for repeated
 * Point-in-Polygon tests and safe for concurrent reads - and (b) memoizes
 * whether any boundary is installed, so a parallel rebuild no longer issues one
 * {@code COUNT} per media.
 *
 * <p>
 * Bounded on purpose: beyond {@code geometryCacheMaxSize} entries, overflow
 * polygons are parsed on demand without being cached, so a worldwide dataset is
 * never fully loaded into RAM. Boundary ids are database identities (monotonic,
 * never reused after a delete), so a stale entry can never be read as a
 * different polygon; {@link #invalidate()} is still called on import/removal to
 * free memory and refresh availability.
 */
@Slf4j
@Component
public class BoundaryGeometryCache {

	private final GeoAdminBoundaryRepository repository;
	private final int maxSize;

	private final Map<Long, PreparedGeometry> geometries = new ConcurrentHashMap<>();
	private final GeometryFactory geometryFactory = new GeometryFactory();
	private final AtomicReference<Boolean> available = new AtomicReference<>();

	public BoundaryGeometryCache(GeoAdminBoundaryRepository repository, LocationRebuildProperties properties) {
		this.repository = repository;
		this.maxSize = Math.max(0, properties.getGeometryCacheMaxSize());
	}

	/**
	 * Prepared polygon of a boundary, cached by id. Transient boundaries without an
	 * id (only ever seen in tests) are parsed without being cached. Returns
	 * {@code null} when the stored WKB cannot be parsed.
	 */
	public PreparedGeometry geometry(GeoAdminBoundary boundary) {
		Long id = boundary.getId();

		if (id == null) {
			return parse(boundary);
		}

		PreparedGeometry cached = geometries.get(id);

		if (cached != null) {
			return cached;
		}

		PreparedGeometry prepared = parse(boundary);

		if (prepared != null && geometries.size() < maxSize) {
			geometries.putIfAbsent(id, prepared);
		}

		return prepared;
	}

	/** Whether any boundary is installed; memoized until {@link #invalidate()}. */
	public boolean available() {
		Boolean current = available.get();

		if (current != null) {
			return current;
		}

		boolean computed = repository.count() > 0;

		available.compareAndSet(null, computed);

		return available.get();
	}

	/** Drops all cached state; called after the dataset is imported or removed. */
	public void invalidate() {
		geometries.clear();
		available.set(null);
	}

	private PreparedGeometry parse(GeoAdminBoundary boundary) {
		try {
			Geometry geometry = new WKBReader(geometryFactory).read(boundary.getGeometry());

			return PreparedGeometryFactory.prepare(geometry);
		} catch (Exception e) {
			log.debug("Could not parse WKB for boundary id={}", boundary.getId(), e);

			return null;
		}
	}
}