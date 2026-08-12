package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.assertj.core.api.Assertions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import br.com.jorgemelo.nimbusfilemanager.shared.TestPostgres;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What the write door is for, which no single-connection test can show.
 *
 * <p>
 * Two features deciding a destination is free and both taking it is the defect
 * the door exists to make impossible, and it only happens when two transactions
 * overlap. Everything here therefore commits, which is why it keeps a container
 * of its own instead of joining the shared one - see
 * {@code SharedPostgresIntegrationTest} for why a committing test may not.
 *
 * <p>
 * No Spring context either. What is under test is the ordering inside the
 * database, and starting a context to reach it would cost forty seconds to prove
 * something JDBC can ask directly.
 *
 * <p>
 * Paths are spelled out rather than taken from a temporary folder, which is safe
 * precisely because nothing here normalises them: the flavour is passed to the
 * function explicitly, so the same strings mean the same thing on both operating
 * systems.
 */
@Testcontainers
class CatalogLocationRaceIntegrationTest {

	/**
	 * In a folder of its own, because the door tells a move from a rename by
	 * exactly that: a move leaves its folder, and one that does not is refused
	 * before any of this is reached.
	 */
	private static final String DESTINATION = "/biblioteca/destino/alvo.jpg";
	private static final String FIRST_ORIGIN = "/biblioteca/primeiro.jpg";
	private static final String SECOND_ORIGIN = "/biblioteca/segundo.jpg";
	private static final String POSIX = "POSIX";

	@Container
	private static final PostgreSQLContainer<?> POSTGRES = TestPostgres.container();

	private static boolean migrated;

	/**
	 * The second attempt must not merely fail - it must <em>wait</em>. A refusal
	 * that arrives without waiting would mean the two never serialized and the
	 * outcome happened to fall the right way, which is the same thing as no lock at
	 * all.
	 */
	@Test
	void twoTransactionsClaimingOnePathAreSerializedAndOnlyOneWins() throws Exception {
		migrate();

		long first = seed(FIRST_ORIGIN);
		long second = seed(SECOND_ORIGIN);

		CountDownLatch started = new CountDownLatch(1);

		ExecutorService executor = Executors.newSingleThreadExecutor();

		try (Connection holder = connect()) {
			holder.setAutoCommit(false);

			move(holder, first, FIRST_ORIGIN);

			Future<String> contender = executor.submit(() -> claimAndReportRefusal(second, started));

			Assertions.assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
			Assertions.assertThat(contender.isDone()).as("the second attempt should still be waiting").isFalse();

			holder.commit();

			Assertions.assertThat(contender.get(30, TimeUnit.SECONDS))
					.as("the second attempt should have been refused as occupied").isEqualTo("NB004");
		} finally {
			executor.shutdownNow();
		}

		Assertions.assertThat(presentFilesAt(DESTINATION)).isEqualTo(1);
		Assertions.assertThat(eventsFor(DESTINATION)).isEqualTo(1);
	}

	/**
	 * {@code CURRENT_TIMESTAMP} is the transaction's, so this is the only place the
	 * placement's stamp can be seen to move at all.
	 */
	@Test
	void thePlacementStampMovesWhenTheChangeIsACommittedOneOfItsOwn() throws Exception {
		migrate();

		long file = seed("/carimbo/a.jpg");

		Timestamp before = updatedAtOf(file);

		try (Connection connection = connect()) {
			rename(connection, file, "/carimbo/a.jpg", "/carimbo/b.jpg");
		}

		Assertions.assertThat(updatedAtOf(file)).isAfter(before);
	}

	/**
	 * The SQLSTATE rather than the exception, because that is what the contract is
	 * - and because AssertJ cannot tell which {@code assertThat} a
	 * {@link SQLException} wants, it being an {@code Iterable} of itself.
	 */
	private String claimAndReportRefusal(long catalogFileId, CountDownLatch started) {
		try (Connection connection = connect()) {
			started.countDown();

			move(connection, catalogFileId, SECOND_ORIGIN);

			return null;
		} catch (SQLException exception) {
			return exception.getSQLState();
		}
	}

	private void move(Connection connection, long catalogFileId, String from) throws SQLException {
		call(connection, """
				SELECT 1 FROM move_catalog_file(?, ?, ?, ?, ?, ROW(now(), 'TEST', 'NIMBUS_OPERATION',
						NULL, NULL, NULL)::catalog_fact_provenance)
				""", catalogFileId, from, DESTINATION);
	}

	private void rename(Connection connection, long catalogFileId, String from, String to) throws SQLException {
		call(connection, """
				SELECT 1 FROM rename_catalog_file(?, ?, ?, ?, ?, ROW(now(), 'TEST', 'NIMBUS_OPERATION',
						NULL, NULL, NULL)::catalog_fact_provenance)
				""", catalogFileId, from, to);
	}

	private void call(Connection connection, String sql, long catalogFileId, String from, String to)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, catalogFileId);
			statement.setObject(2, UUID.randomUUID());
			statement.setString(3, from);
			statement.setString(4, to);
			statement.setString(5, POSIX);

			statement.executeQuery().close();
		}
	}

	private long seed(String path) throws SQLException {
		try (Connection connection = connect()) {
			long file;

			try (PreparedStatement statement = connection.prepareStatement("""
					INSERT INTO catalog_file (catalog_file_public_id, extension, size_bytes, modified_at, file_type,
					        lifecycle_status)
					VALUES (?, 'jpg', 1024, now(), 'PHOTO', 'ACTIVE')
					RETURNING id
					""")) {
				statement.setObject(1, UUID.randomUUID());

				try (ResultSet rows = statement.executeQuery()) {
					rows.next();

					file = rows.getLong(1);
				}
			}

			try (PreparedStatement statement = connection.prepareStatement("""
					INSERT INTO catalog_file_location (catalog_file_id, current_path, path_flavor)
					VALUES (?, ?, ?)
					""")) {
				statement.setLong(1, file);
				statement.setString(2, path);
				statement.setString(3, POSIX);

				statement.executeUpdate();
			}

			return file;
		}
	}

	private long presentFilesAt(String path) throws SQLException {
		return count("""
				SELECT count(*) FROM catalog_file_location l
				JOIN catalog_file m ON m.id = l.catalog_file_id
				WHERE l.path_key = canonicalize_catalog_path(?, 'POSIX') AND m.lifecycle_status = 'ACTIVE'
				""", path);
	}

	private long eventsFor(String path) throws SQLException {
		return count("SELECT count(*) FROM catalog_file_event WHERE new_path = ?", path);
	}

	private long count(String sql, String argument) throws SQLException {
		try (Connection connection = connect();
				PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, argument);

			try (ResultSet rows = statement.executeQuery()) {
				rows.next();

				return rows.getLong(1);
			}
		}
	}

	private Timestamp updatedAtOf(long catalogFileId) throws SQLException {
		try (Connection connection = connect();
				PreparedStatement statement = connection
						.prepareStatement("SELECT updated_at FROM catalog_file_location WHERE catalog_file_id = ?")) {
			statement.setLong(1, catalogFileId);

			try (ResultSet rows = statement.executeQuery()) {
				rows.next();

				return rows.getTimestamp(1);
			}
		}
	}

	private Connection connect() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	private static synchronized void migrate() {
		if (migrated) {
			return;
		}

		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").load().migrate();

		migrated = true;
	}
}