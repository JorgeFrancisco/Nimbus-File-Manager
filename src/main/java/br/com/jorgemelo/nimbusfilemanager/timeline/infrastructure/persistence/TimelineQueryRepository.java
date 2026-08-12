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
import br.com.jorgemelo.nimbusfilemanager.geolocation.application.LocationLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineCountSummary;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineMonthCount;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.enums.GeoPresence;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection.CaptureWindow;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection.MediaScaleFilter;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection.TimelineFilter;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection.TimelineItemProjection;

@Repository
public class TimelineQueryRepository {

	/**
	 * The predicates every timeline query shares, so a filter is written once
	 * instead of four times. Each is inert when its parameter is null, which is how
	 * a timeline with no filters produces exactly the SQL it always produced.
	 *
	 * <p>
	 * The camera match is a case-insensitive prefix: EXIF stores "Canon EOS 5D Mark
	 * III" and a person types "canon". The longest-side comparison uses GREATEST so
	 * that "at least Full HD" does not depend on the photo being landscape.
	 */
	private static final String SHARED_FILTERS = """
			  AND (CAST(:fileType AS varchar) IS NULL OR mf.file_type = CAST(:fileType AS varchar))
			  AND m.subcategory IN (:subcategories)
			  AND (CAST(:manufacturer AS varchar) IS NULL
			       OR LOWER(m.manufacturer) LIKE LOWER(CAST(:manufacturer AS varchar)) || '%')
			  AND (CAST(:model AS varchar) IS NULL
			       OR LOWER(m.model) LIKE LOWER(CAST(:model AS varchar)) || '%')
			  AND (CAST(:minBytes AS bigint) IS NULL OR mf.size_bytes >= CAST(:minBytes AS bigint))
			  AND (CAST(:maxBytes AS bigint) IS NULL OR mf.size_bytes <= CAST(:maxBytes AS bigint))
			  AND (CAST(:minDuration AS double precision) IS NULL
			       OR v.duration_seconds >= CAST(:minDuration AS double precision))
			  AND (CAST(:maxDuration AS double precision) IS NULL
			       OR v.duration_seconds <= CAST(:maxDuration AS double precision))
			  AND (CAST(:minLongestSide AS integer) IS NULL
			       OR GREATEST(COALESCE(m.display_width, 0), COALESCE(m.display_height, 0))
			          >= CAST(:minLongestSide AS integer))
			  AND (CAST(:geo AS varchar) IS NULL
			       OR (CAST(:geo AS varchar) = 'WITH_LOCATION' AND gl.catalog_file_id IS NOT NULL)
			       OR (CAST(:geo AS varchar) = 'WITHOUT_LOCATION' AND gl.catalog_file_id IS NULL))
			""";

	private static final String SELECT_TIMELINE_ITEMS = """
			SELECT mf.id AS internal_id,
			       mf.catalog_file_public_id,
			       catalog_file_name(location.current_path, location.path_flavor) AS file_name,
			       mf.file_type,
			       m.capture_date,
			       m.date_source,
			       m.display_width,
			       m.display_height,
			       v.duration_seconds,
			       gl.city_name, gl.state_name, gl.country_name, gl.open_sea
			FROM media_metadata m
			JOIN catalog_file mf ON mf.id = m.catalog_file_id
			JOIN catalog_file_location location ON location.catalog_file_id = mf.id
			LEFT JOIN video v ON v.catalog_file_id = mf.id
			LEFT JOIN media_geo_location gl ON gl.catalog_file_id = mf.id
			WHERE mf.lifecycle_status = 'ACTIVE'
			  AND mf.file_type IN ('PHOTO', 'VIDEO')
			  AND m.capture_date IS NOT NULL
			  AND (CAST(:captureFrom AS timestamp) IS NULL OR m.capture_date >= CAST(:captureFrom AS timestamp))
			  AND (CAST(:captureTo AS timestamp) IS NULL OR m.capture_date < CAST(:captureTo AS timestamp))
			""" + SHARED_FILTERS + """
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
			JOIN catalog_file_location location ON location.catalog_file_id = mf.id
			LEFT JOIN video v ON v.catalog_file_id = mf.id
			LEFT JOIN media_geo_location gl ON gl.catalog_file_id = mf.id
			WHERE mf.lifecycle_status = 'ACTIVE'
			  AND mf.file_type IN ('PHOTO', 'VIDEO')
			  AND m.capture_date IS NOT NULL
			  AND (CAST(:captureFrom AS timestamp) IS NULL OR m.capture_date >= CAST(:captureFrom AS timestamp))
			  AND (CAST(:captureTo AS timestamp) IS NULL OR m.capture_date < CAST(:captureTo AS timestamp))
			""" + SHARED_FILTERS + """
			GROUP BY m.year, m.month
			ORDER BY m.year DESC, m.month DESC
			""";

	private static final String SELECT_UNDATED_ITEMS = """
			SELECT mf.id AS internal_id, mf.catalog_file_public_id,
			       catalog_file_name(location.current_path, location.path_flavor) AS file_name, mf.file_type,
			       m.capture_date, m.date_source, m.display_width, m.display_height,
			       v.duration_seconds,
			       gl.city_name, gl.state_name, gl.country_name, gl.open_sea
			FROM media_metadata m
			JOIN catalog_file mf ON mf.id = m.catalog_file_id
			JOIN catalog_file_location location ON location.catalog_file_id = mf.id
			LEFT JOIN video v ON v.catalog_file_id = mf.id
			LEFT JOIN media_geo_location gl ON gl.catalog_file_id = mf.id
			WHERE mf.lifecycle_status = 'ACTIVE'
			  AND mf.file_type IN ('PHOTO', 'VIDEO')
			  AND m.capture_date IS NULL
			""" + SHARED_FILTERS + """
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
			JOIN catalog_file_location location ON location.catalog_file_id = mf.id
			LEFT JOIN video v ON v.catalog_file_id = mf.id
			LEFT JOIN media_geo_location gl ON gl.catalog_file_id = mf.id
			WHERE mf.lifecycle_status = 'ACTIVE'
			  AND mf.file_type IN ('PHOTO', 'VIDEO')
			""" + SHARED_FILTERS;

	private final NamedParameterJdbcTemplate jdbcTemplate;
	private final LocationLabels locationLabels;

	public TimelineQueryRepository(NamedParameterJdbcTemplate jdbcTemplate, LocationLabels locationLabels) {
		this.jdbcTemplate = jdbcTemplate;
		this.locationLabels = locationLabels;
	}

	public List<TimelineItemProjection> findPage(FileType fileType, Collection<String> subcategories,
			TimelineFilter filter, LocalDateTime cursorDate, Long cursorId, int limit) {
		validateCursor(cursorDate, cursorId);

		if (limit < 1) {
			throw new IllegalArgumentException("Timeline page limit must be positive");
		}

		var parameters = baseParameters(fileType, subcategories, filter)
				.addValue("cursorDate", cursorDate == null ? null : Timestamp.valueOf(cursorDate), Types.TIMESTAMP)
				.addValue("cursorId", cursorId, Types.BIGINT).addValue("limit", limit, Types.INTEGER);

		return jdbcTemplate.query(SELECT_TIMELINE_ITEMS, parameters, (rs, _) -> mapItem(rs));
	}

	public List<TimelineMonthCount> findMonthCounts(FileType fileType, Collection<String> subcategories,
			TimelineFilter filter) {
		var parameters = baseParameters(fileType, subcategories, filter);

		return jdbcTemplate.query(SELECT_MONTH_COUNTS, parameters,
				(rs, _) -> new TimelineMonthCount(rs.getInt("year"), rs.getInt("month"), rs.getLong("item_count")));
	}

	public TimelineCountSummary findCountSummary(FileType fileType, Collection<String> subcategories,
			TimelineFilter filter) {
		var parameters = baseParameters(fileType, subcategories, filter);
		List<TimelineCountSummary> summaries = jdbcTemplate.query(SELECT_COUNT_SUMMARY, parameters,
				(rs, _) -> new TimelineCountSummary(rs.getLong("total_items"), rs.getLong("dated_items"),
						rs.getLong("undated_items")));

		return summaries.isEmpty() ? new TimelineCountSummary(0, 0, 0) : summaries.getFirst();
	}

	public List<TimelineItemProjection> findUndatedPage(FileType fileType, Collection<String> subcategories,
			TimelineFilter filter, Long cursorId, int limit) {
		if (limit < 1) {
			throw new IllegalArgumentException("Timeline page limit must be positive");
		}

		var parameters = baseParameters(fileType, subcategories, filter).addValue("cursorId", cursorId, Types.BIGINT)
				.addValue("limit", limit, Types.INTEGER);

		return jdbcTemplate.query(SELECT_UNDATED_ITEMS, parameters, (rs, _) -> mapItem(rs));
	}

	private TimelineItemProjection mapItem(ResultSet rs) throws SQLException {
		Number duration = (Number) rs.getObject("duration_seconds");

		Timestamp captureDate = rs.getTimestamp("capture_date");
		String dateSource = rs.getString("date_source");
		return new TimelineItemProjection(rs.getLong("internal_id"), rs.getObject("catalog_file_public_id", UUID.class),
				rs.getString("file_name"), FileType.valueOf(rs.getString("file_type")),
				captureDate == null ? null : captureDate.toLocalDateTime(),
				dateSource == null ? null : DateSource.valueOf(dateSource), (Integer) rs.getObject("display_width"),
				(Integer) rs.getObject("display_height"), duration == null ? null : duration.doubleValue(),
				LocationDisplay.shortLabel(rs.getString("city_name"), rs.getString("state_name"),
						rs.getString("country_name"), rs.getBoolean("open_sea"), locationLabels.openSea()));
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
	private MapSqlParameterSource baseParameters(FileType fileType, Collection<String> subcategories,
			TimelineFilter filter) {
		if (subcategories == null || subcategories.isEmpty()) {
			throw new IllegalArgumentException("Timeline subcategory filter must not be empty");
		}

		TimelineFilter narrowing = filter == null ? TimelineFilter.NONE : filter;
		CaptureWindow window = narrowing.window();
		MediaScaleFilter scale = narrowing.scale();

		// The window is asked as whole days and bound as an instant: "to" becomes the
		// start of the following day, so the last day is included rather than cut at
		// midnight - which is what a person means by "up to the 31st".
		return new MapSqlParameterSource()
				.addValue("fileType", fileType == null ? null : fileType.name(), Types.VARCHAR)
				.addValue("subcategories", subcategories)
				.addValue("captureFrom", window.from() == null ? null : Timestamp.valueOf(window.from().atStartOfDay()),
						Types.TIMESTAMP)
				.addValue("captureTo",
						window.to() == null ? null : Timestamp.valueOf(window.to().plusDays(1).atStartOfDay()),
						Types.TIMESTAMP)
				.addValue("manufacturer", blankToNull(narrowing.camera().manufacturer()), Types.VARCHAR)
				.addValue("model", blankToNull(narrowing.camera().model()), Types.VARCHAR)
				.addValue("minBytes", scale.minBytes(), Types.BIGINT)
				.addValue("maxBytes", scale.maxBytes(), Types.BIGINT)
				.addValue("minDuration", scale.minDurationSeconds(), Types.DOUBLE)
				.addValue("maxDuration", scale.maxDurationSeconds(), Types.DOUBLE)
				.addValue("minLongestSide", scale.minLongestSide(), Types.INTEGER)
				.addValue("geo", narrowing.geo() == GeoPresence.ANY ? null : narrowing.geo().name(), Types.VARCHAR);
	}

	/**
	 * A field left empty on the screen is "any", never a search for the empty
	 * string.
	 */
	private String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}