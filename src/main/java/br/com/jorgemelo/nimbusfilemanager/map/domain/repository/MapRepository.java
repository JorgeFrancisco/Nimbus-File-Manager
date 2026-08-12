package br.com.jorgemelo.nimbusfilemanager.map.domain.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import br.com.jorgemelo.nimbusfilemanager.map.domain.repository.projection.MapAdministrativePinProjection;
import br.com.jorgemelo.nimbusfilemanager.map.domain.repository.projection.MapExifPinProjection;
import br.com.jorgemelo.nimbusfilemanager.map.domain.repository.projection.MapMediaItemProjection;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;

/**
 * Read-only aggregate queries backing the media map. Pins are always aggregated
 * (never one row per media): EXIF media snap to a square grid whose cell size
 * the caller derives from the zoom (so nearby pins never pile up on screen),
 * and coordinate-less media fall back to their administrative region. Media
 * with EXIF coordinates never contribute to an administrative pin.
 */
public interface MapRepository extends Repository<CatalogFile, Long> {

	@Query(value = """
			SELECT (FLOOR(m.latitude / :cell) + 0.5) * :cell AS lat,
			       (FLOOR(m.longitude / :cell) + 0.5) * :cell AS lon,
			       COUNT(*) AS total,
			       COUNT(*) FILTER (WHERE mf.file_type = 'PHOTO') AS photos,
			       COUNT(*) FILTER (WHERE mf.file_type = 'VIDEO') AS videos,
			       MAX(g.city_name) AS city, MAX(g.state_name) AS state, MAX(g.country_name) AS country,
			       BOOL_OR(g.open_sea) AS openSea,
			       (array_agg(mf.catalog_file_public_id ORDER BY m.capture_date DESC NULLS LAST, mf.id DESC))[1] AS coverId,
			       (array_agg(mf.file_type ORDER BY m.capture_date DESC NULLS LAST, mf.id DESC))[1] AS coverFileType,
			       (array_agg(catalog_file_name(l.current_path, l.path_flavor)
			                  ORDER BY m.capture_date DESC NULLS LAST, mf.id DESC))[1] AS coverFileName
			FROM catalog_file mf
			JOIN media_metadata m ON m.catalog_file_id = mf.id
			LEFT JOIN catalog_file_location l ON l.catalog_file_id = mf.id
			LEFT JOIN media_geo_location g ON g.catalog_file_id = mf.id
			WHERE mf.lifecycle_status = 'ACTIVE' AND m.latitude IS NOT NULL AND m.longitude IS NOT NULL
			GROUP BY 1, 2
			""", nativeQuery = true)
	List<MapExifPinProjection> exifPins(@Param("cell") double cell);

	@Query(value = """
			SELECT (FLOOR(m.latitude / :cell) + 0.5) * :cell AS lat,
			       (FLOOR(m.longitude / :cell) + 0.5) * :cell AS lon,
			       COUNT(*) AS total,
			       COUNT(*) FILTER (WHERE mf.file_type = 'PHOTO') AS photos,
			       COUNT(*) FILTER (WHERE mf.file_type = 'VIDEO') AS videos,
			       MAX(g.city_name) AS city, MAX(g.state_name) AS state, MAX(g.country_name) AS country,
			       BOOL_OR(g.open_sea) AS openSea,
			       (array_agg(mf.catalog_file_public_id ORDER BY m.capture_date DESC NULLS LAST, mf.id DESC))[1] AS coverId,
			       (array_agg(mf.file_type ORDER BY m.capture_date DESC NULLS LAST, mf.id DESC))[1] AS coverFileType,
			       (array_agg(catalog_file_name(l.current_path, l.path_flavor)
			                  ORDER BY m.capture_date DESC NULLS LAST, mf.id DESC))[1] AS coverFileName
			FROM catalog_file mf
			JOIN media_metadata m ON m.catalog_file_id = mf.id
			LEFT JOIN catalog_file_location l ON l.catalog_file_id = mf.id
			LEFT JOIN media_geo_location g ON g.catalog_file_id = mf.id
			WHERE mf.lifecycle_status = 'ACTIVE'
			  AND m.latitude BETWEEN :minLat AND :maxLat AND m.longitude BETWEEN :minLon AND :maxLon
			GROUP BY 1, 2
			ORDER BY COUNT(*) DESC
			LIMIT :limit
			""", nativeQuery = true)
	List<MapExifPinProjection> exifPinsInBounds(@Param("minLat") double minLat, @Param("minLon") double minLon,
			@Param("maxLat") double maxLat, @Param("maxLon") double maxLon, @Param("cell") double cell,
			@Param("limit") int limit);

	@Query(value = """
			SELECT g.country_code AS countryCode, g.state_name AS stateName, g.city_name AS cityName,
			       COUNT(*) AS total,
			       COUNT(*) FILTER (WHERE mf.file_type = 'PHOTO') AS photos,
			       COUNT(*) FILTER (WHERE mf.file_type = 'VIDEO') AS videos,
			       (array_agg(mf.catalog_file_public_id ORDER BY m.capture_date DESC NULLS LAST, mf.id DESC))[1] AS coverId,
			       (array_agg(mf.file_type ORDER BY m.capture_date DESC NULLS LAST, mf.id DESC))[1] AS coverFileType,
			       (array_agg(catalog_file_name(l.current_path, l.path_flavor)
			                  ORDER BY m.capture_date DESC NULLS LAST, mf.id DESC))[1] AS coverFileName
			FROM catalog_file mf
			JOIN media_metadata m ON m.catalog_file_id = mf.id
			LEFT JOIN catalog_file_location l ON l.catalog_file_id = mf.id
			JOIN media_geo_location g ON g.catalog_file_id = mf.id
			WHERE mf.lifecycle_status = 'ACTIVE' AND m.latitude IS NULL
			GROUP BY g.country_code, g.state_name, g.city_name
			""", nativeQuery = true)
	List<MapAdministrativePinProjection> administrativePins();

	@Query(value = """
			SELECT mf.catalog_file_public_id AS publicId, mf.file_type AS fileType,
			       catalog_file_name(l.current_path, l.path_flavor) AS fileName,
			       m.capture_date AS captureDate
			FROM catalog_file mf
			JOIN catalog_file_location l ON l.catalog_file_id = mf.id
			JOIN media_metadata m ON m.catalog_file_id = mf.id
			WHERE mf.lifecycle_status = 'ACTIVE'
			  AND m.latitude >= :minLat AND m.latitude < :maxLat
			  AND m.longitude >= :minLon AND m.longitude < :maxLon
			ORDER BY m.capture_date DESC NULLS LAST, mf.id DESC
			""", countQuery = """
			SELECT COUNT(*) FROM catalog_file mf JOIN media_metadata m ON m.catalog_file_id = mf.id
			WHERE mf.lifecycle_status = 'ACTIVE'
			  AND m.latitude >= :minLat AND m.latitude < :maxLat
			  AND m.longitude >= :minLon AND m.longitude < :maxLon
			""", nativeQuery = true)
	Page<MapMediaItemProjection> exifPinItems(@Param("minLat") double minLat, @Param("maxLat") double maxLat,
			@Param("minLon") double minLon, @Param("maxLon") double maxLon, Pageable pageable);

	@Query(value = """
			SELECT mf.catalog_file_public_id AS publicId, mf.file_type AS fileType,
			       catalog_file_name(l.current_path, l.path_flavor) AS fileName,
			       m.capture_date AS captureDate
			FROM catalog_file mf
			JOIN catalog_file_location l ON l.catalog_file_id = mf.id
			JOIN media_metadata m ON m.catalog_file_id = mf.id
			JOIN media_geo_location g ON g.catalog_file_id = mf.id
			WHERE mf.lifecycle_status = 'ACTIVE' AND m.latitude IS NULL
			  AND g.country_code IS NOT DISTINCT FROM :countryCode
			  AND g.state_name IS NOT DISTINCT FROM :stateName
			  AND g.city_name IS NOT DISTINCT FROM :cityName
			ORDER BY m.capture_date DESC NULLS LAST, mf.id DESC
			""", countQuery = """
			SELECT COUNT(*) FROM catalog_file mf JOIN media_metadata m ON m.catalog_file_id = mf.id
			JOIN media_geo_location g ON g.catalog_file_id = mf.id
			WHERE mf.lifecycle_status = 'ACTIVE' AND m.latitude IS NULL
			  AND g.country_code IS NOT DISTINCT FROM :countryCode
			  AND g.state_name IS NOT DISTINCT FROM :stateName
			  AND g.city_name IS NOT DISTINCT FROM :cityName
			""", nativeQuery = true)
	Page<MapMediaItemProjection> administrativePinItems(@Param("countryCode") String countryCode,
			@Param("stateName") String stateName, @Param("cityName") String cityName, Pageable pageable);
}