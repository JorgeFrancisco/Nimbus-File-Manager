package br.com.jorgemelo.nimbusfilemanager.backup.infrastructure.persistence;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * What the catalog schema looks like right now, for the backup manifest.
 *
 * <p>
 * The dump itself is taken by {@code pg_dump} and carries its own structure;
 * what the manifest adds is the pair a restore has to check before touching
 * anything - which schema version produced the file, and which tables it was
 * expected to hold.
 *
 * <p>
 * The table list comes from the database catalog rather than a hardcoded
 * enumeration, so a table introduced by a future migration is described the day
 * it exists - a list written by hand is a list that silently stops being
 * complete.
 */
@Repository
public class CatalogSchemaRepository {

	/**
	 * Flyway's own bookkeeping: it describes the database being restored into,
	 * not the one the backup came from, so it is never part of the manifest.
	 */
	private static final String EXCLUDED = "flyway_schema_history";

	private static final String TABLES = """
			SELECT table_name FROM information_schema.tables
			WHERE table_schema = 'public' AND table_type = 'BASE TABLE' AND table_name <> :excluded
			ORDER BY table_name
			""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public CatalogSchemaRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public List<String> tables() {
		return jdbcTemplate.queryForList(TABLES, Map.of("excluded", EXCLUDED), String.class);
	}

	/** Current schema version, as Flyway recorded it. */
	public String schemaVersion() {
		List<String> versions = jdbcTemplate.queryForList(
				"SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
				Map.of(), String.class);

		return versions.isEmpty() ? "unknown" : versions.get(0);
	}
}