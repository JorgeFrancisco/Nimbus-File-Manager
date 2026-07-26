package br.com.jorgemelo.nimbusfilemanager.map.application;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.io.WKBReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.LocationDisplay;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.LocationLabels;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.AdminBoundaryKind;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.GeoAdminBoundary;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository.GeoAdminBoundaryRepository;
import br.com.jorgemelo.nimbusfilemanager.map.application.dto.MapBounds;
import br.com.jorgemelo.nimbusfilemanager.map.application.dto.MapMediaItem;
import br.com.jorgemelo.nimbusfilemanager.map.application.dto.MapPin;
import br.com.jorgemelo.nimbusfilemanager.map.application.dto.MapPinSource;
import br.com.jorgemelo.nimbusfilemanager.map.domain.repository.MapRepository;
import br.com.jorgemelo.nimbusfilemanager.map.domain.repository.projection.MapAdministrativePinProjection;
import br.com.jorgemelo.nimbusfilemanager.map.domain.repository.projection.MapExifPinProjection;
import br.com.jorgemelo.nimbusfilemanager.map.domain.repository.projection.MapMediaItemProjection;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;

/**
 * Builds the aggregated pins for the media map and resolves the paginated media
 * of a pin. EXIF media pin at their real (4-decimal rounded) coordinate;
 * coordinate-less media fall back to the representative point of their deepest
 * known administrative region (municipality &gt; state &gt; country). The
 * {@code pinId} is an opaque, self-describing token so the client never parses
 * a coordinate/region contract.
 */
@Service
public class MapService {

	private static final Logger log = LoggerFactory.getLogger(MapService.class);

	private static final char SEP = '\n';

	/**
	 * Degrees spanned by a ~64px cell at zoom 0: {@code 64 * 360 / 256}. Divided by
	 * {@code 2^zoom} it gives the grid cell size at any zoom (a tile is 256px and
	 * spans 360deg at zoom 0).
	 */
	private static final double PIXELS_PER_CELL_DEGREES = 90.0;

	/** Finest grid cell (~11 m), the floor used at street-level zoom. */
	private static final double FINE_CELL = 0.0001;

	private final MapRepository mapRepository;
	private final LocationLabels locationLabels;
	private final GeoAdminBoundaryRepository boundaryRepository;
	private final GeometryFactory geometryFactory = new GeometryFactory();

	public MapService(MapRepository mapRepository, GeoAdminBoundaryRepository boundaryRepository,
			LocationLabels locationLabels) {
		this.mapRepository = mapRepository;
		this.boundaryRepository = boundaryRepository;
		this.locationLabels = locationLabels;
	}

	public List<MapPin> pins() {
		return pins(mapRepository.exifPins(FINE_CELL), null, FINE_CELL);
	}

	/**
	 * Pins visible in the given bounding box, aggregated into a square grid whose
	 * cell size comes from the zoom so nearby EXIF pins merge into one instead of
	 * piling up on screen. EXIF pins are filtered (and capped) in the database;
	 * administrative pins are filtered here by their representative point, since
	 * that point is derived from the region geometry rather than stored.
	 */
	public List<MapPin> pins(MapBounds bounds, int limit, int zoom) {
		double cell = cellForZoom(zoom);

		return pins(mapRepository.exifPinsInBounds(bounds.minLat(), bounds.minLon(), bounds.maxLat(), bounds.maxLon(),
				cell, limit), bounds, cell);
	}

	private List<MapPin> pins(List<MapExifPinProjection> exifRows, MapBounds bounds, double cell) {
		List<MapPin> pins = new ArrayList<>();

		for (MapExifPinProjection row : exifRows) {
			pins.add(exifPin(row, cell));
		}

		for (MapAdministrativePinProjection row : mapRepository.administrativePins()) {
			administrativePin(row).filter(pin -> bounds == null || contains(bounds, pin)).ifPresent(pins::add);
		}

		return pins;
	}

	private boolean contains(MapBounds bounds, MapPin pin) {
		return pin.latitude() >= bounds.minLat() && pin.latitude() <= bounds.maxLat()
				&& pin.longitude() >= bounds.minLon() && pin.longitude() <= bounds.maxLon();
	}

	public Page<MapMediaItem> items(String pinId, Pageable pageable) {
		String[] parts = decode(pinId);

		Page<MapMediaItemProjection> page = switch (parts[0]) {
		case "E" -> exifPinItems(parts, pageable);
		case "A" -> mapRepository.administrativePinItems(blankToNull(parts[1]), blankToNull(parts[2]),
				blankToNull(parts[3]), pageable);
		default -> throw new IllegalArgumentException("Unknown pin id.");
		};

		return page.map(item -> new MapMediaItem(item.getPublicId(), FileType.valueOf(item.getFileType()),
				item.getFileName(), item.getCaptureDate()));
	}

	private MapPin exifPin(MapExifPinProjection row, double cell) {
		double lat = row.getLat();
		double lon = row.getLon();

		String place = LocationDisplay.fullLabel(row.getCity(), row.getState(), row.getCountry(),
				Boolean.TRUE.equals(row.getOpenSea()), locationLabels.openSea());
		String label = place != null ? place : coordinateLabel(lat, lon);

		// The pin id carries the grid cell (size + centre) so its media can be fetched
		// back as the range that exactly covers the cell, independent of the zoom in
		// effect when the pin is later clicked.
		String pinId = encode("E", Double.toString(cell), Double.toString(lat), Double.toString(lon));

		return new MapPin(pinId, MapPinSource.EXIF, lat, lon, label, row.getTotal(), row.getPhotos(), row.getVideos(),
				row.getCoverId(), toFileType(row.getCoverFileType()), row.getCoverFileName());
	}

	private Page<MapMediaItemProjection> exifPinItems(String[] parts, Pageable pageable) {
		double cell = Double.parseDouble(parts[1]);
		double lat = Double.parseDouble(parts[2]);
		double lon = Double.parseDouble(parts[3]);
		double half = cell / 2;

		return mapRepository.exifPinItems(lat - half, lat + half, lon - half, lon + half, pageable);
	}

	private Optional<MapPin> administrativePin(MapAdministrativePinProjection row) {
		Optional<GeoAdminBoundary> boundary = deepestBoundary(row.getCountryCode(), row.getStateName(),
				row.getCityName());

		if (boundary.isEmpty()) {
			return Optional.empty();
		}

		double[] point = representativePoint(boundary.get());

		String label = LocationDisplay.fullLabel(row.getCityName(), row.getStateName(),
				boundary.get().getCountryName());

		String pinId = encode("A", nullToBlank(row.getCountryCode()), nullToBlank(row.getStateName()),
				nullToBlank(row.getCityName()));

		return Optional.of(new MapPin(pinId, MapPinSource.ADMINISTRATIVE, point[0], point[1],
				label != null ? label : boundary.get().getName(), row.getTotal(), row.getPhotos(), row.getVideos(),
				row.getCoverId(), toFileType(row.getCoverFileType()), row.getCoverFileName()));
	}

	private Optional<GeoAdminBoundary> deepestBoundary(String countryCode, String stateName, String cityName) {
		if (countryCode == null || countryCode.isBlank()) {
			return Optional.empty();
		}

		if (cityName != null && !cityName.isBlank() && stateName != null && !stateName.isBlank()) {
			Optional<GeoAdminBoundary> city = boundaryRepository
					.findFirstByKindAndCountryCodeIgnoreCaseAndStateNameIgnoreCaseAndNameIgnoreCase(
							AdminBoundaryKind.MUNICIPALITY, countryCode, stateName, cityName);

			if (city.isPresent()) {
				return city;
			}
		}

		if (stateName != null && !stateName.isBlank()) {
			Optional<GeoAdminBoundary> state = boundaryRepository
					.findFirstByKindAndCountryCodeIgnoreCaseAndNameIgnoreCase(AdminBoundaryKind.STATE, countryCode,
							stateName);

			if (state.isPresent()) {
				return state;
			}
		}

		return boundaryRepository.findFirstByKindAndCountryCodeIgnoreCase(AdminBoundaryKind.COUNTRY, countryCode);
	}

	/**
	 * Representative point of a region: interior point (guaranteed inside the
	 * polygon), then centroid, then the bounding-box centre as a last resort.
	 * Returns {@code [lat, lon]}.
	 */
	private double[] representativePoint(GeoAdminBoundary boundary) {
		try {
			Geometry geometry = new WKBReader(geometryFactory).read(boundary.getGeometry());

			Point point = geometry.getInteriorPoint();

			if (point == null || point.isEmpty()) {
				point = geometry.getCentroid();
			}

			if (point != null && !point.isEmpty()) {
				return new double[] { point.getY(), point.getX() };
			}
		} catch (Exception e) {
			log.debug("Could not derive representative point for boundary id={}", boundary.getId(), e);
		}

		return new double[] { (boundary.getMinLat() + boundary.getMaxLat()) / 2,
				(boundary.getMinLon() + boundary.getMaxLon()) / 2 };
	}

	private String coordinateLabel(double lat, double lon) {
		return String.format(Locale.ROOT, "%.4f, %.4f", lat, lon);
	}

	/**
	 * Grid cell size (in degrees) for a zoom level. Chosen so a cell is about 64
	 * screen pixels wide (a marker is 58px), which keeps occupied cells from
	 * overlapping; it never drops below {@link #FINE_CELL} (~11 m) at street zoom.
	 */
	private double cellForZoom(int zoom) {
		return Math.max(FINE_CELL, PIXELS_PER_CELL_DEGREES / Math.pow(2, Math.max(0, zoom)));
	}

	private String encode(String... parts) {
		String joined = String.join(String.valueOf(SEP), parts);

		return Base64.getUrlEncoder().withoutPadding().encodeToString(joined.getBytes(StandardCharsets.UTF_8));
	}

	private String[] decode(String pinId) {
		try {
			String joined = new String(Base64.getUrlDecoder().decode(pinId), StandardCharsets.UTF_8);

			return joined.split(String.valueOf(SEP), -1);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Invalid pin id.", e);
		}
	}

	private FileType toFileType(String value) {
		return value == null ? null : FileType.valueOf(value);
	}

	private String nullToBlank(String value) {
		return value == null ? "" : value;
	}

	private String blankToNull(String value) {
		return value == null || value.isEmpty() ? null : value;
	}
}