package br.com.jorgemelo.nimbusfilemanager.media.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.LocationDisplay;
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.LocationLabels;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.MediaContentSource;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.MediaDetails;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;

@Repository
public class MediaContentRepository {

	private static final String BASE = """
			FROM catalog_file mf
			JOIN media_metadata m ON m.catalog_file_id = mf.id
			LEFT JOIN video v ON v.catalog_file_id = mf.id
			LEFT JOIN media_geo_location gl ON gl.catalog_file_id = mf.id
			JOIN LATERAL (
			 SELECT ml.current_path, ml.path_flavor FROM catalog_file_location ml WHERE ml.catalog_file_id = mf.id
			 ORDER BY ml.updated_at DESC, ml.catalog_file_id DESC LIMIT 1
			) location ON TRUE
			WHERE mf.catalog_file_public_id = :publicId AND mf.lifecycle_status IN ('ACTIVE', 'DELETED')
			  AND mf.file_type IN ('PHOTO', 'VIDEO')
			""";

	private final NamedParameterJdbcTemplate jdbcTemplate;
	private final LocationLabels locationLabels;

	public MediaContentRepository(NamedParameterJdbcTemplate jdbcTemplate, LocationLabels locationLabels) {
		this.jdbcTemplate = jdbcTemplate;
		this.locationLabels = locationLabels;
	}

	public Optional<MediaContentSource> findContent(UUID publicId) {
		String sql = """
				SELECT mf.catalog_file_public_id, location.current_path,
				       catalog_file_name(location.current_path, location.path_flavor) AS file_name,
				       mf.mime_type, mf.file_type
				FROM catalog_file mf
				JOIN LATERAL (
				 SELECT ml.current_path, ml.path_flavor FROM catalog_file_location ml WHERE ml.catalog_file_id = mf.id
				 ORDER BY ml.updated_at DESC, ml.catalog_file_id DESC LIMIT 1
				) location ON TRUE
				WHERE mf.catalog_file_public_id = :publicId AND mf.lifecycle_status IN ('ACTIVE', 'DELETED')
				""";
		List<MediaContentSource> rows = jdbcTemplate.query(sql, params(publicId),
				(rs, _) -> new MediaContentSource(rs.getObject("catalog_file_public_id", UUID.class),
						rs.getString("current_path"), rs.getString("file_name"), rs.getString("mime_type"),
						FileType.valueOf(rs.getString("file_type"))));
		return rows.stream().findFirst();
	}

	public Optional<MediaDetails> findDetails(UUID publicId) {
		String sql = """
				SELECT mf.catalog_file_public_id,
				       catalog_file_name(location.current_path, location.path_flavor) AS file_name,
				       mf.file_type, m.capture_date, m.date_source,
				       mf.created_at, mf.modified_at, m.display_width, m.display_height,
				       m.manufacturer, m.model, m.latitude, m.longitude, v.duration_seconds,
				       location.current_path,
				       gl.city_name, gl.state_name, gl.country_name, gl.open_sea, gl.distance_km, gl.confidence, gl.provider
				"""
				+ BASE;
		List<MediaDetails> rows = jdbcTemplate.query(sql, params(publicId), (rs, _) -> {
			Number duration = (Number) rs.getObject("duration_seconds");
			Number latitude = (Number) rs.getObject("latitude");
			Number longitude = (Number) rs.getObject("longitude");
			Number distanceKm = (Number) rs.getObject("distance_km");

			var captureDate = rs.getTimestamp("capture_date");

			String dateSource = rs.getString("date_source");

			return new MediaDetails(rs.getObject("catalog_file_public_id", UUID.class), rs.getString("file_name"),
					FileType.valueOf(rs.getString("file_type")),

					captureDate == null ? null : captureDate.toLocalDateTime(),
					dateSource == null ? null : DateSource.valueOf(dateSource),

					rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime(),
					rs.getTimestamp("modified_at").toLocalDateTime(), (Integer) rs.getObject("display_width"),

					(Integer) rs.getObject("display_height"), rs.getString("manufacturer"), rs.getString("model"),

					latitude == null ? null : latitude.doubleValue(),
					longitude == null ? null : longitude.doubleValue(),
					duration == null ? null : duration.doubleValue(), rs.getString("current_path"),

					"/api/media/" + publicId + "/content",

					LocationDisplay.fullLabel(rs.getString("city_name"), rs.getString("state_name"),
							rs.getString("country_name"), rs.getBoolean("open_sea"), locationLabels.openSea()),

					distanceKm == null ? null : distanceKm.doubleValue(),

					// Location labels and the type/date-source labels are localized in the service
					// layer; the repository only exposes the raw confidence and provider codes.
					null, rs.getString("confidence"), rs.getString("provider"), null, null);
		});
		return rows.stream().findFirst();
	}

	private MapSqlParameterSource params(UUID publicId) {
		return new MapSqlParameterSource("publicId", publicId);
	}
}