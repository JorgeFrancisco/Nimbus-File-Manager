package br.com.jorgemelo.nimbusfilemanager.thumbnail.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import br.com.jorgemelo.nimbusfilemanager.thumbnail.application.dto.VideoThumbnailSource;

@Repository
public class VideoThumbnailRepository {

	private static final String SELECT_SOURCE = """
			SELECT mf.catalog_file_public_id, location.current_path, mf.content_revision, v.duration_seconds
			FROM catalog_file mf
			JOIN video v ON v.catalog_file_id = mf.id
			JOIN LATERAL (
			    SELECT ml.current_path FROM catalog_file_location ml
			    WHERE ml.catalog_file_id = mf.id
			    ORDER BY ml.updated_at DESC, ml.catalog_file_id DESC LIMIT 1
			) location ON TRUE
			WHERE mf.catalog_file_public_id = :publicId AND mf.file_type = 'VIDEO'
			  AND mf.lifecycle_status IN ('ACTIVE', 'DELETED')
			""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public VideoThumbnailRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Optional<VideoThumbnailSource> findSource(UUID publicId) {
		List<VideoThumbnailSource> sources = jdbcTemplate.query(SELECT_SOURCE,
				new MapSqlParameterSource("publicId", publicId), (rs, _) -> {
					Number duration = (Number) rs.getObject("duration_seconds");
					return new VideoThumbnailSource(rs.getObject("catalog_file_public_id", UUID.class),
							rs.getString("current_path"), rs.getLong("content_revision"),
							duration == null ? null : duration.doubleValue());
				});

		return sources.stream().findFirst();
	}
}