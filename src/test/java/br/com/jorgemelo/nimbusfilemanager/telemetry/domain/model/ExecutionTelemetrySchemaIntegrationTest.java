package br.com.jorgemelo.nimbusfilemanager.telemetry.domain.model;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;

/**
 * The shape telemetry rests on, proved against a real PostgreSQL.
 *
 * <p>
 * Written against JDBC rather than the entities because what is being proved is
 * the database's own guarantees: a constraint the mapping cannot enforce, a
 * cascade Hibernate is not asked to perform, a column the application must not
 * be able to leave empty. An entity test would prove that Hibernate does what
 * Hibernate does; this proves what survives when it is not in the room.
 */
class ExecutionTelemetrySchemaIntegrationTest extends SharedPostgresIntegrationTest {

	@Autowired
	private DataSource dataSource;

	/** The aggregate is one row per execution, and the execution says which. */
	@Test
	void theAggregateIsIdentifiedByItsExecution() throws SQLException {
		Assertions.assertThat(primaryKeyOf("execution_metrics")).containsExactly("execution_id");
	}

	/**
	 * A row that does not say which attempt produced it is a row nothing can be
	 * fenced against, so the column cannot be left out.
	 */
	@Test
	void anAggregateMustNameTheAttemptThatProducedIt() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			long execution = execution(connection);

			Assertions
					.assertThatThrownBy(() -> update(connection,
							"INSERT INTO execution_metrics (execution_id) VALUES (?)", execution))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("attempt_claim_count");
		}
	}

	/** Consolidation replaces, so a phase can only be there once. */
	@Test
	void anExecutionCannotHaveTheSamePhaseTwice() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			long execution = execution(connection);

			phase(connection, execution, "EXTRACTION", 10);

			Assertions.assertThatThrownBy(() -> phase(connection, execution, "EXTRACTION", 20))
					.isInstanceOf(SQLException.class);
		}
	}

	@Test
	void anExecutionCannotHaveTheSameCategoryTwice() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			long execution = execution(connection);

			category(connection, execution, "FFMPEG_PHOTO_HASH", 1);

			Assertions.assertThatThrownBy(() -> category(connection, execution, "FFMPEG_PHOTO_HASH", 2))
					.isInstanceOf(SQLException.class);
		}
	}

	/**
	 * The uniqueness is per execution, not per phase: two runs measure the same
	 * phases, and a constraint that forbade it would let only one run be measured.
	 */
	@Test
	void twoExecutionsMeasureTheSamePhasesAndCategoriesIndependently() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			long first = execution(connection);
			long second = execution(connection);

			phase(connection, first, "EXTRACTION", 10);
			phase(connection, second, "EXTRACTION", 20);

			category(connection, first, "FFMPEG_PHOTO_HASH", 1);
			category(connection, second, "FFMPEG_PHOTO_HASH", 2);

			Assertions.assertThat(phaseCount(connection, first)).isEqualTo(1);
			Assertions.assertThat(phaseCount(connection, second)).isEqualTo(1);
			Assertions.assertThat(categoryRuns(connection, first)).containsExactly(1L);
			Assertions.assertThat(categoryRuns(connection, second)).containsExactly(2L);
		}
	}

	/**
	 * Telemetry describes an execution and outlives nothing: deleting the run
	 * takes the aggregate, the phases and the categories with it, in one statement
	 * the application never has to remember to write.
	 */
	@Test
	void deletingAnExecutionTakesEveryPieceOfItsTelemetry() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			long execution = execution(connection);

			aggregate(connection, execution, 1);
			phase(connection, execution, "PERSISTENCE", 30);
			category(connection, execution, "FFPROBE_VIDEO", 4);

			update(connection, "DELETE FROM execution WHERE id = ?", execution);

			Assertions.assertThat(countOf(connection, "execution_metrics", execution)).isZero();
			Assertions.assertThat(countOf(connection, "execution_phase", execution)).isZero();
			Assertions.assertThat(countOf(connection, "execution_metrics_category", execution)).isZero();
		}
	}

	/** The dead photo-hash counters are gone from the table, not merely unused. */
	@Test
	void theCountersNothingEverWroteAreNotInTheTable() throws SQLException {
		// Non-empty first: a query that returned no columns at all would pass this
		// without the table having been read, which is the one way it could lie.
		Assertions.assertThat(columnsOf("execution_metrics")).isNotEmpty().doesNotContain(
				"photo_hash_jvm_decodable", "photo_hash_ffmpeg_only", "photo_hash_failures");
	}

	private long execution(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO execution (execution_public_id, execution_type, status, created_at, available_at,"
						+ " claim_count) VALUES (gen_random_uuid(), 'INVENTORY', 'FINISHED', now(), now(), 1)"
						+ " RETURNING id")) {
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();

				return rows.getLong(1);
			}
		}
	}

	private void aggregate(Connection connection, long execution, int claimCount) throws SQLException {
		update(connection, "INSERT INTO execution_metrics (execution_id, attempt_claim_count) VALUES (?, ?)", execution,
				claimCount);
	}

	private void phase(Connection connection, long execution, String phase, long millis) throws SQLException {
		update(connection, "INSERT INTO execution_phase (execution_id, phase, duration_millis, items) "
				+ "VALUES (?, ?, ?, 0)", execution, phase, millis);
	}

	private void category(Connection connection, long execution, String category, long runs) throws SQLException {
		update(connection, "INSERT INTO execution_metrics_category (execution_id, category, runs) VALUES (?, ?, ?)",
				execution, category, runs);
	}

	private void update(Connection connection, String sql, Object... parameters) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int index = 0; index < parameters.length; index++) {
				statement.setObject(index + 1, parameters[index]);
			}

			statement.executeUpdate();
		}
	}

	private int countOf(Connection connection, String table, long execution) throws SQLException {
		try (PreparedStatement statement = connection
				.prepareStatement("SELECT count(*) FROM " + table + " WHERE execution_id = ?")) {
			statement.setLong(1, execution);

			try (ResultSet rows = statement.executeQuery()) {
				rows.next();

				return rows.getInt(1);
			}
		}
	}

	private int phaseCount(Connection connection, long execution) throws SQLException {
		return countOf(connection, "execution_phase", execution);
	}

	private List<Long> categoryRuns(Connection connection, long execution) throws SQLException {
		List<Long> runs = new ArrayList<>();

		try (PreparedStatement statement = connection
				.prepareStatement("SELECT runs FROM execution_metrics_category WHERE execution_id = ?")) {
			statement.setLong(1, execution);

			try (ResultSet rows = statement.executeQuery()) {
				while (rows.next()) {
					runs.add(rows.getLong(1));
				}
			}
		}

		return runs;
	}

	private List<String> primaryKeyOf(String table) throws SQLException {
		return query("SELECT a.attname FROM pg_index i"
				+ " JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)"
				+ " WHERE i.indrelid = '" + table + "'::regclass AND i.indisprimary");
	}

	private List<String> columnsOf(String table) throws SQLException {
		return query("SELECT column_name FROM information_schema.columns WHERE table_name = '" + table + "'");
	}

	private List<String> query(String sql) throws SQLException {
		List<String> values = new ArrayList<>();

		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery(sql)) {
			while (rows.next()) {
				values.add(rows.getString(1));
			}
		}

		return values;
	}
}