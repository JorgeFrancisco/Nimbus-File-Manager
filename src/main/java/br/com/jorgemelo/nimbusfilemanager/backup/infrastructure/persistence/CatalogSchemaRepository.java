package br.com.jorgemelo.nimbusfilemanager.backup.infrastructure.persistence;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.SchemaHistoryRepository;

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

	private static final String UNKNOWN_VERSION = "unknown";

	private final NamedParameterJdbcTemplate jdbcTemplate;
	private final SchemaHistoryRepository schemaHistoryRepository;

	public CatalogSchemaRepository(NamedParameterJdbcTemplate jdbcTemplate,
			SchemaHistoryRepository schemaHistoryRepository) {
		this.jdbcTemplate = jdbcTemplate;
		this.schemaHistoryRepository = schemaHistoryRepository;
	}

	public List<String> tables() {
		return jdbcTemplate.queryForList(TABLES, Map.of("excluded", EXCLUDED), String.class);
	}

	/**
	 * Gives the planner statistics for the rows a restore just loaded.
	 *
	 * <p>
	 * {@code pg_restore} brings data and indexes, never statistics: until
	 * autovacuum gets round to each table, the planner sizes them at a default and
	 * plans blind. On an empty installation nobody notices; on a restored catalog
	 * of a hundred thousand files, a query planned in that window picks a shape
	 * that never finishes - the first restore on a real backup left the perceptual
	 * hash backlog running one for seven minutes, on a plan that takes
	 * milliseconds once the statistics exist.
	 *
	 * <p>
	 * Cheap by comparison: seconds, on a sample. It is what the PostgreSQL
	 * documentation asks of anyone who restores.
	 */
	public void analyze() {
		jdbcTemplate.getJdbcTemplate().execute("ANALYZE");
	}

	/**
	 * Current schema version, as Flyway recorded it, for the manifest. A database
	 * Flyway never migrated has no version to name, and the manifest says so
	 * rather than leaving the field out.
	 */
	public String schemaVersion() {
		return schemaHistoryRepository.currentVersion().orElse(UNKNOWN_VERSION);
	}
}