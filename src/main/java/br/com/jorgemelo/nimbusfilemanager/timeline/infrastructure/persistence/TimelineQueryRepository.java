package br.com.jorgemelo.nimbusfilemanager.timeline.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.LocationDisplay;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineCountSummary;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineMonthCount;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection.TimelineItemProjection;

@Repository
public class TimelineQueryRepository {

	private static final String SELECT_TIMELINE_ITEMS = """
			SELECT mf.id AS internal_id,
			       mf.public_id,
			       mf.file_name,
			       mf.file_type,
			       m.capture_date,
			       m.date_source,
			       m.display_width,
			       m.display_height,
			       v.duration_seconds,
			       gl.city_name, gl.state_name, gl.country_name
			FROM media_metadata m
			JOIN catalog_file mf ON mf.id = m.catalog_file_id
			LEFT JOIN video v ON v.catalog_file_id = mf.id
			LEFT JOIN media_geo_location gl ON gl.catalog_file_id = mf.id
			WHERE mf.lifecycle_status = 'ACTIVE'
			  AND mf.file_type IN ('PHOTO', 'VIDEO')
			  AND m.capture_date IS NOT NULL
			  AND (CAST(:fileType AS varchar) IS NULL OR mf.file_type = CAST(:fileType AS varchar))
			  AND m.subcategory IN (:subcategories)
			  AND (
			       CAST(:cursorDate AS timestamp) IS NULL
			       OR (m.capture_date, m.catalog_file_id) <
			          (CAST(:cursorDate AS timestamp), CAST(:cursorId AS bigint))
			  )
			ORDER BY m.capture_date DESC, mf.id DESC
			LIMIT :limit
			""";

	private static final String SELECT_MONTH_COUNTS = """
			SELECT m.year, m.month, COUNT(*) AS item_count
			FROM media_metadata m
			JOIN catalog_file mf ON mf.id = m.catalog_file_id
			WHERE mf.lifecycle_status = 'ACTIVE'
			  AND mf.file_type IN ('PHOTO', 'VIDEO')
			  AND m.capture_date IS NOT NULL
			  AND (CAST(:fileType AS varchar) IS NULL OR mf.file_type = CAST(:fileType AS varchar))
			  AND m.subcategory IN (:subcategories)
			GROUP BY m.year, m.month
			ORDER BY m.year DESC, m.month DESC
			""";

	private static final String SELECT_UNDATED_ITEMS = """
			SELECT mf.id AS internal_id, mf.public_id, mf.file_name, mf.file_type,
			       m.capture_date, m.date_source, m.display_width, m.display_height,
			       v.duration_seconds,
			       gl.city_name, gl.state_name, gl.country_name
			FROM media_metadata m
			JOIN catalog_file mf ON mf.id = m.catalog_file_id
			LEFT JOIN video v ON v.catalog_file_id = mf.id
			LEFT JOIN media_geo_location gl ON gl.catalog_file_id = mf.id
			WHERE mf.lifecycle_status = 'ACTIVE'
			  AND mf.file_type IN ('PHOTO', 'VIDEO')
			  AND m.capture_date IS NULL
			  AND (CAST(:fileType AS varchar) IS NULL OR mf.file_type = CAST(:fileType AS varchar))
			  AND m.subcategory IN (:subcategories)
			  AND (CAST(:cursorId AS bigint) IS NULL OR mf.id < CAST(:cursorId AS bigint))
			ORDER BY mf.id DESC
			LIMIT :limit
			""";

	private static final String SELECT_COUNT_SUMMARY = """
			SELECT COUNT(*) AS total_items,
			       COUNT(m.capture_date) AS dated_items,
			       COUNT(*) - COUNT(m.capture_date) AS undated_items
			FROM media_metadata m
			JOIN catalog_file mf ON mf.id = m.catalog_file_id
			WHERE mf.lifecycle_status = 'ACTIVE'
			  AND mf.file_type IN ('PHOTO', 'VIDEO')
			  AND (CAST(:fileType AS varchar) IS NULL OR mf.file_type = CAST(:fileType AS varchar))
			  AND m.subcategory IN (:subcategories)
			""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public TimelineQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<TimelineItemProjection> findPage(FileType fileType, Collection<String> subcategories,
			LocalDateTime cursorDate, Long cursorId, int limit) {
		validateCursor(cursorDate, cursorId);

		if (limit < 1) {
			throw new IllegalArgumentException("Timeline page limit must be positive");
		}

		var parameters = baseParameters(fileType, subcategories)
				.addValue("cursorDate", cursorDate == null ? null : Timestamp.valueOf(cursorDate), Types.TIMESTAMP)
				.addValue("cursorId", cursorId, Types.BIGINT).addValue("limit", limit, Types.INTEGER);

		return jdbcTemplate.query(SELECT_TIMELINE_ITEMS, parameters, (rs, _) -> mapItem(rs));
	}

	public List<TimelineMonthCount> findMonthCounts(FileType fileType, Collection<String> subcategories) {
		var parameters = baseParameters(fileType, subcategories);

		return jdbcTemplate.query(SELECT_MONTH_COUNTS, parameters,
				(rs, _) -> new TimelineMonthCount(rs.getInt("year"), rs.getInt("month"), rs.getLong("item_count")));
	}

	public TimelineCountSummary findCountSummary(FileType fileType, Collection<String> subcategories) {
		var parameters = baseParameters(fileType, subcategories);
		List<TimelineCountSummary> summaries = jdbcTemplate.query(SELECT_COUNT_SUMMARY, parameters,
				(rs, _) -> new TimelineCountSummary(rs.getLong("total_items"), rs.getLong("dated_items"),
						rs.getLong("undated_items")));

		return summaries.isEmpty() ? new TimelineCountSummary(0, 0, 0) : summaries.getFirst();
	}

	public List<TimelineItemProjection> findUndatedPage(FileType fileType, Collection<String> subcategories,
			Long cursorId, int limit) {
		if (limit < 1) {
			throw new IllegalArgumentException("Timeline page limit must be positive");
		}

		var parameters = baseParameters(fileType, subcategories).addValue("cursorId", cursorId, Types.BIGINT)
				.addValue("limit", limit, Types.INTEGER);

		return jdbcTemplate.query(SELECT_UNDATED_ITEMS, parameters, (rs, _) -> mapItem(rs));
	}

	private TimelineItemProjection mapItem(ResultSet rs) throws SQLException {
		Number duration = (Number) rs.getObject("duration_seconds");

		Timestamp captureDate = rs.getTimestamp("capture_date");
		String dateSource = rs.getString("date_source");
		return new TimelineItemProjection(rs.getLong("internal_id"), rs.getObject("public_id", UUID.class),
				rs.getString("file_name"), FileType.valueOf(rs.getString("file_type")),
				captureDate == null ? null : captureDate.toLocalDateTime(),
				dateSource == null ? null : DateSource.valueOf(dateSource), (Integer) rs.getObject("display_width"),
				(Integer) rs.getObject("display_height"), duration == null ? null : duration.doubleValue(),
				LocationDisplay.shortLabel(rs.getString("city_name"), rs.getString("state_name"),
						rs.getString("country_name")));
	}

	private void validateCursor(LocalDateTime cursorDate, Long cursorId) {
		if ((cursorDate == null) != (cursorId == null)) {
			throw new IllegalArgumentException("Timeline cursor date and id must be provided together");
		}
	}

	/**
	 * Shared bindings for every timeline query: the optional media-type filter and
	 * the subcategory whitelist. The subcategory collection must be non-empty (the
	 * service passes every subcategory when the user filters nothing), since an
	 * empty {@code IN ()} is invalid SQL.
	 */
	private MapSqlParameterSource baseParameters(FileType fileType, Collection<String> subcategories) {
		if (subcategories == null || subcategories.isEmpty()) {
			throw new IllegalArgumentException("Timeline subcategory filter must not be empty");
		}

		return new MapSqlParameterSource()
				.addValue("fileType", fileType == null ? null : fileType.name(), Types.VARCHAR)
				.addValue("subcategories", subcategories);
	}
}