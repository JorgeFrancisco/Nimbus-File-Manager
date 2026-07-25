package br.com.jorgemelo.nimbusfilemanager.geolocation.infrastructure.persistence;

import java.util.List;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * JDBC write access for the boundary import. Deliberately set-based and native:
 * a worldwide dataset must not build a JPA session, so the bulk delete and the
 * batched inserts go straight through {@link NamedParameterJdbcTemplate}
 * instead of {@code geo_admin_boundary}'s JPA repository. The importer keeps
 * the streaming/progress orchestration; this class owns only the SQL.
 */
@Repository
public class GeoAdminBoundaryImportRepository {

	private static final String INSERT = """
			INSERT INTO geo_admin_boundary (kind, name, country_code, country_name, state_name,
			                                min_lat, min_lon, max_lat, max_lon, geometry, source, dataset_version)
			VALUES (:kind, :name, :countryCode, :countryName, :stateName,
			        :minLat, :minLon, :maxLat, :maxLon, :geometry, :source, :datasetVersion)
			""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public GeoAdminBoundaryImportRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** Empties the table before a full dataset reimport. */
	public void deleteAll() {
		jdbcTemplate.getJdbcTemplate().update("DELETE FROM geo_admin_boundary");
	}

	/** Flushes one batch of boundary rows in a single JDBC round-trip. */
	public void batchInsert(List<SqlParameterSource> rows) {
		jdbcTemplate.batchUpdate(INSERT, rows.toArray(SqlParameterSource[]::new));
	}
}