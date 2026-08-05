package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * What Flyway says this database has been migrated to.
 *
 * <p>
 * Asked by two features that have nothing else in common: a backup records the
 * version it was taken from, and a worker refuses to claim work from a schema
 * its build was not made for. The question is the same one, so the statement
 * lives here rather than being written twice and drifting once.
 */
@Repository
public class SchemaHistoryRepository {

	private static final String CURRENT_VERSION = """
			SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1
			""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public SchemaHistoryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * @return the last version Flyway applied successfully, or empty on a database
	 * it has never migrated
	 */
	public Optional<String> currentVersion() {
		List<String> versions = jdbcTemplate.queryForList(CURRENT_VERSION, Map.of(), String.class);

		return versions.isEmpty() ? Optional.empty() : Optional.of(versions.get(0));
	}
}