package br.com.jorgemelo.nimbusfilemanager.backup.infrastructure.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes whole tables through PostgreSQL's own {@code COPY}, over the
 * connection the application already has.
 *
 * <p>
 * This is what lets the catalog be backed up without {@code pg_dump}: adding a
 * second external binary to install and keep in step with the server version
 * would cost more than it protects, and the application had just finished
 * removing one such dependency. {@code COPY} is the same mechanism
 * {@code pg_dump} uses for data, reached from the driver already on the
 * classpath.
 *
 * <p>
 * The table list comes from the database catalog rather than a hardcoded
 * enumeration, so a table introduced by a future migration is backed up the day
 * it exists - a list written by hand is a list that silently stops being
 * complete.
 */
@Repository
public class CatalogCopyRepository {

	/**
	 * Flyway's own bookkeeping: restoring it would overwrite the history of the
	 * database being restored into, which is the one thing that must keep
	 * describing the schema actually present.
	 */
	private static final String EXCLUDED = "flyway_schema_history";

	private static final String TABLES = """
			SELECT table_name FROM information_schema.tables
			WHERE table_schema = 'public' AND table_type = 'BASE TABLE' AND table_name <> :excluded
			ORDER BY table_name
			""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public CatalogCopyRepository(NamedParameterJdbcTemplate jdbcTemplate) {
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

	/** Writes one table as CSV with a header row. */
	public long copyOut(String table, OutputStream output) {
		return copy(
				manager -> manager.copyOut("COPY " + quoted(table) + " TO STDOUT WITH (FORMAT csv, HEADER)", output));
	}

	/** Loads one table from CSV with a header row, into an empty table. */
	public long copyIn(String table, InputStream input) {
		return copy(
				manager -> manager.copyIn("COPY " + quoted(table) + " FROM STDIN WITH (FORMAT csv, HEADER)", input));
	}

	/**
	 * Empties every table of the schema. The loop runs inside the database, over
	 * the database's own catalog, so no identifier is ever concatenated into SQL on
	 * this side. {@code CASCADE} because the tables reference each other and an
	 * order written by hand would go stale at the next migration;
	 * {@code RESTART IDENTITY} because the restored rows carry their own ids.
	 */
	public void truncateAll() {
		jdbcTemplate.getJdbcTemplate().execute("""
				DO $$
				DECLARE row record;
				BEGIN
				  FOR row IN
				    SELECT tablename FROM pg_tables
				    WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
				  LOOP
				    EXECUTE format('TRUNCATE TABLE %I RESTART IDENTITY CASCADE', row.tablename);
				  END LOOP;
				END $$;
				""");
	}

	/**
	 * Sequences are restarted by the truncate above, so after loading rows that
	 * carry their own ids the next insert would collide. This puts each sequence
	 * back past the largest id its table holds.
	 */
	public void realignSequences() {
		jdbcTemplate.getJdbcTemplate().execute("""
				DO $$
				DECLARE row record;
				BEGIN
				  FOR row IN
				    SELECT s.relname AS sequence_name, t.relname AS table_name, a.attname AS column_name
				    FROM pg_class s
				    JOIN pg_depend d ON d.objid = s.oid
				    JOIN pg_class t ON t.oid = d.refobjid
				    JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = d.refobjsubid
				    WHERE s.relkind = 'S'
				  LOOP
				    EXECUTE format('SELECT setval(%L, COALESCE((SELECT MAX(%I) FROM %I), 0) + 1, false)',
				                   row.sequence_name, row.column_name, row.table_name);
				  END LOOP;
				END $$;
				""");
	}

	/**
	 * Table names come from the database catalog, never from user input, but they
	 * are quoted anyway: an identifier interpolated into SQL unquoted is a habit
	 * that outlives the reason it was safe.
	 */
	private String quoted(String table) {
		return '"' + table.replace("\"", "\"\"") + '"';
	}

	/**
	 * Borrows the connection through the template so acquisition and release stay
	 * with the transaction the caller is in - a backup runs inside one, and a
	 * connection taken outside it would read a different snapshot.
	 */
	private long copy(CopyOperation operation) {
		Long rows = jdbcTemplate.getJdbcTemplate().execute((ConnectionCallback<Long>) connection -> {
			try {
				return operation.run(new CopyManager(connection.unwrap(BaseConnection.class)));
			} catch (IOException e) {
				throw new IllegalStateException("Copy failed: " + e.getMessage(), e);
			}
		});

		return rows == null ? 0 : rows;
	}
}