package br.com.jorgemelo.nimbusfilemanager.backup.application;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.DatabaseConnection;

/**
 * The url the application connects with, split into what the command-line tools
 * take.
 *
 * <p>
 * {@code pg_dump} and {@code pg_restore} are given a host, a port and a
 * database name, and they are given exactly what comes out of here. Getting any
 * of the three wrong does not fail loudly: it dumps a different database than
 * the one the application is using, or restores over it.
 *
 * <p>
 * The port carries the most risk of the three. The embedded server picks its
 * own at first start, and a Testcontainers connection picks another, so the
 * value has to come from the url the application actually connected with rather
 * than from any default.
 */
class JdbcUrlsTest {

	@Test
	void splitsTheUrlIntoWhatTheToolsAreGiven() {
		DatabaseConnection connection = JdbcUrls.parse("jdbc:postgresql://localhost:5433/nimbus", "app", "secret");

		Assertions.assertThat(connection)
				.extracting(DatabaseConnection::host, DatabaseConnection::port, DatabaseConnection::database)
				.containsExactly("localhost", 5433, "nimbus");
	}

	/** The credentials are carried through untouched; only the url is parsed. */
	@Test
	void carriesTheCredentialsItWasGiven() {
		DatabaseConnection connection = JdbcUrls.parse("jdbc:postgresql://localhost:5432/nimbus", "app", "secret");

		Assertions.assertThat(connection.username()).isEqualTo("app");
		Assertions.assertThat(connection.password()).isEqualTo("secret");
	}

	/** A url that names no port is talking to the one PostgreSQL listens on. */
	@Test
	void aUrlWithoutAPortMeansTheDefaultOne() {
		Assertions.assertThat(JdbcUrls.parse("jdbc:postgresql://localhost/nimbus", "app", "secret").port())
				.isEqualTo(5432);
	}

	/**
	 * The prefix is JDBC's own and means nothing to a URI, so it is dropped before
	 * parsing - with it, everything after {@code jdbc:} would be opaque and the
	 * host, port and database would all come back empty.
	 */
	@Test
	void readsTheSameUrlWithOrWithoutTheJdbcPrefix() {
		Assertions.assertThat(JdbcUrls.parse("postgresql://localhost:5433/nimbus", "app", "secret"))
				.isEqualTo(JdbcUrls.parse("jdbc:postgresql://localhost:5433/nimbus", "app", "secret"));
	}

	/** A url that names no host is talking to this machine. */
	@Test
	void aUrlWithoutAHostMeansThisMachine() {
		Assertions.assertThat(JdbcUrls.parse("jdbc:postgresql:///nimbus", "app", "secret").host())
				.isEqualTo("127.0.0.1");
	}

	/**
	 * No database in the url. Answered as empty rather than guessed at: the tools
	 * are given what the url said, and inventing a name here would aim a dump at a
	 * database nobody named.
	 */
	@Test
	void aUrlThatNamesNoDatabaseAnswersWithNone() {
		Assertions.assertThat(JdbcUrls.parse("jdbc:postgresql://localhost:5432/", "app", "secret").database())
				.isEmpty();
		Assertions.assertThat(JdbcUrls.parse("jdbc:postgresql://localhost:5432", "app", "secret").database())
				.isEmpty();
	}

	/** Connection parameters belong to the driver, not to the database name. */
	@Test
	void leavesTheDriversOwnParametersOutOfTheDatabaseName() {
		Assertions.assertThat(JdbcUrls
				.parse("jdbc:postgresql://localhost:5432/nimbus?sslmode=require", "app", "secret").database())
				.isEqualTo("nimbus");
	}
}