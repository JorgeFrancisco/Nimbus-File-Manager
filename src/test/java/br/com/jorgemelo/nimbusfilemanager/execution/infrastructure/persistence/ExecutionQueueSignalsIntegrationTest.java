package br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionChannels;

/**
 * The worker's wake-up call, against a real PostgreSQL because there is
 * nothing else to test it against: what is under test is a session holding a
 * {@code LISTEN} and a driver handing over what arrived on it.
 *
 * <p>
 * It exists because this class used to be covered by accident. The listening
 * thread starts with the worker context and ends in {@code @PreDestroy}, so
 * the shutdown half only ever ran when the Spring TestContext cache evicted a
 * context mid-build - which stopped happening once the suite fitted inside the
 * cache. Coverage that depends on how many contexts a build happens to create
 * is not coverage of anything, so the behaviour is asserted here instead.
 *
 * <p>
 * No Spring context on purpose: the collaborator is a {@code DataSource} and
 * the subject is a database session, so a context would add a minute to the
 * build and prove nothing extra.
 */
@Testcontainers
class ExecutionQueueSignalsIntegrationTest {

	/**
	 * Comfortably longer than the listener's own block, so that a pass is a pass
	 * rather than a race won.
	 */
	private static final Duration GENEROUS = Duration.ofSeconds(10);

	@Container
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

	private PGSimpleDataSource dataSource;
	private ExecutionQueueSignals signals;

	@BeforeEach
	void startListening() {
		dataSource = new PGSimpleDataSource();

		dataSource.setUrl(postgres.getJdbcUrl());
		dataSource.setUser(postgres.getUsername());
		dataSource.setPassword(postgres.getPassword());

		signals = new ExecutionQueueSignals(dataSource);

		signals.start();

		// The subscription is established off-thread and announced by one signal.
		// Waiting for it is what makes every assertion after it deterministic
		// rather than dependent on how fast the container answers.
		Assertions.assertThat(signals.awaitSignalAfter(0, GENEROUS)).as("the listener subscribed").isTrue();
	}

	@AfterEach
	void stopListening() {
		signals.stop();
	}

	@Test
	void aNotificationOnTheChannelReachesWhoeverIsWaiting() throws SQLException {
		long seen = signals.signalCount();

		notifyQueued();

		Assertions.assertThat(signals.awaitSignalAfter(seen, GENEROUS)).isTrue();
		Assertions.assertThat(signals.signalCount()).isGreaterThan(seen);
	}

	/** No news is not an error: the caller goes and asks the queue anyway. */
	@Test
	void aBudgetThatRunsOutWithoutANotificationIsTheOrdinaryIdleCase() {
		long seen = signals.signalCount();

		Assertions.assertThat(signals.awaitSignalAfter(seen, Duration.ofMillis(200))).isFalse();
		Assertions.assertThat(signals.signalCount()).isEqualTo(seen);
	}

	/**
	 * Stopping joins the thread before it returns, so a notification published
	 * afterwards has nobody left to reach. Stopping twice is what a context
	 * closed twice would do, and it has to be silent.
	 */
	@Test
	void stoppingEndsTheListenerAndSaysNothingTheSecondTime() throws SQLException {
		signals.stop();
		signals.stop();

		long seen = signals.signalCount();

		notifyQueued();

		Assertions.assertThat(signals.awaitSignalAfter(seen, Duration.ofMillis(500))).isFalse();
	}

	private void notifyQueued() throws SQLException {
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("NOTIFY " + ExecutionChannels.QUEUED);
		}
	}
}