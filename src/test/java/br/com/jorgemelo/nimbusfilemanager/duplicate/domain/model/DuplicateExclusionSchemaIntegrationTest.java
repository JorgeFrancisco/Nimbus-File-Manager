package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;

/**
 * The shape {@code duplicate_exclusion_file} really has, asked of PostgreSQL
 * rather than read off the migration.
 *
 * <p>
 * Three of its rules are the database's alone and no Java can be made to show
 * them: that a file may carry one judgement and not two, that a judgement cannot
 * name a file the catalog does not have, and that removing the file takes the
 * judgement with it. The service reads as though it upheld the first - it asks
 * before it writes - but a check followed by a write is not a rule; the
 * constraint is, and this is where it is proved.
 *
 * <p>
 * <b>Its own connection, and nothing committed.</b> Two of the statements below
 * are meant to be refused, and a refusal poisons the transaction it happens in -
 * the test's own, if it borrowed one, taking every assertion after it down. So
 * each test gets a connection with auto-commit off, rolled back afterwards,
 * which also leaves the shared database as clean as the rollback of a
 * transactional test would.
 */
class DuplicateExclusionSchemaIntegrationTest extends SharedPostgresIntegrationTest {

	@Autowired
	private DataSource dataSource;

	private Connection connection;

	@BeforeEach
	void open() throws SQLException {
		connection = dataSource.getConnection();

		connection.setAutoCommit(false);
	}

	@AfterEach
	void discard() throws SQLException {
		connection.rollback();

		connection.close();
	}

	/**
	 * Excluding a file twice is the same statement made twice. The table says so,
	 * so no service can leave two judgements about one file even by racing with
	 * itself.
	 */
	@Test
	void aFileCarriesOneJudgementAndTheDatabaseIsWhatSaysSo() throws SQLException {
		long file = catalogFile();

		judgement(file, 1L);

		Assertions.assertThatThrownBy(() -> judgement(file, 2L))
				.as("the second insert is refused whatever revision it carries").isInstanceOf(SQLException.class)
				.hasMessageContaining("uk_duplicate_exclusion_file_catalog_file");
	}

	/** One per file, not one in the table: two files are two judgements. */
	@Test
	void twoFilesEachCarryTheirOwn() throws SQLException {
		judgement(catalogFile(), 1L);
		judgement(catalogFile(), 1L);

		Assertions.assertThat(count("SELECT count(*) FROM duplicate_exclusion_file")).isEqualTo(2L);
	}

	/**
	 * A judgement is about a catalogued file, and there is no such thing as one
	 * about a file the catalog never had.
	 */
	@Test
	void aJudgementCannotNameAFileTheCatalogDoesNotHave() {
		Assertions.assertThatThrownBy(() -> judgement(-1L, 1L)).isInstanceOf(SQLException.class)
				.hasMessageContaining("fk_duplicate_exclusion_file_catalog_file");
	}

	/**
	 * The cascade, which is the whole cleanup story: every way of removing a file
	 * for good - the retention purge, forgetting a library, erasing a quarantined
	 * one - selects {@code catalog_file} and names this table nowhere.
	 */
	@Test
	void removingTheFileTakesTheJudgementWithIt() throws SQLException {
		long file = catalogFile();

		judgement(file, 1L);

		update("DELETE FROM catalog_file WHERE id = ?", file);

		Assertions.assertThat(judgementsAbout(file)).isZero();
	}

	/**
	 * Changing what the catalog says about a file is not removing it. Stated
	 * beside the cascade above, because a cascade wired to the wrong event would
	 * satisfy that test and quietly forget a preference on every soft delete.
	 */
	@Test
	void aLifecycleChangeTakesNothing() throws SQLException {
		long file = catalogFile();

		judgement(file, 1L);

		update("UPDATE catalog_file SET lifecycle_status = 'DELETED' WHERE id = ?", file);

		Assertions.assertThat(judgementsAbout(file)).isOne();
	}

	/**
	 * The pair the applying-or-not comparison reads, in the order it reads them.
	 * Not decoration: the exclusion list is joined against the catalog on every
	 * duplicate query the screen runs.
	 */
	@Test
	void theJudgementIsIndexedByTheFileAndTheGenerationItJudged() throws SQLException {
		Assertions.assertThat(single("""
				SELECT indexdef FROM pg_indexes
				 WHERE tablename = 'duplicate_exclusion_file'
				   AND indexname = 'ix_duplicate_exclusion_file_catalog_file'
				""")).contains("(catalog_file_id, content_revision)");
	}

	private long catalogFile() throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO catalog_file (catalog_file_public_id, extension, size_bytes, modified_at, file_type,
						lifecycle_status)
				VALUES (?, 'jpg', 1024, now(), 'PHOTO', 'ACTIVE')
				RETURNING id
				""")) {
			statement.setObject(1, UUID.randomUUID());

			try (ResultSet rows = statement.executeQuery()) {
				rows.next();

				return rows.getLong(1);
			}
		}
	}

	private void judgement(long catalogFileId, long contentRevision) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO duplicate_exclusion_file (duplicate_exclusion_file_public_id, catalog_file_id,
						content_revision)
				VALUES (?, ?, ?)
				""")) {
			statement.setObject(1, UUID.randomUUID());
			statement.setLong(2, catalogFileId);
			statement.setLong(3, contentRevision);

			statement.executeUpdate();
		}
	}

	private long judgementsAbout(long catalogFileId) throws SQLException {
		try (PreparedStatement statement = connection
				.prepareStatement("SELECT count(*) FROM duplicate_exclusion_file WHERE catalog_file_id = ?")) {
			statement.setLong(1, catalogFileId);

			try (ResultSet rows = statement.executeQuery()) {
				rows.next();

				return rows.getLong(1);
			}
		}
	}

	private long count(String sql) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql);
				ResultSet rows = statement.executeQuery()) {
			rows.next();

			return rows.getLong(1);
		}
	}

	private String single(String sql) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql);
				ResultSet rows = statement.executeQuery()) {
			rows.next();

			return rows.getString(1);
		}
	}

	private void update(String sql, Object... arguments) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int index = 0; index < arguments.length; index++) {
				statement.setObject(index + 1, arguments[index]);
			}

			statement.executeUpdate();
		}
	}
}