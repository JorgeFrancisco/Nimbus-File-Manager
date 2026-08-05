package br.com.jorgemelo.nimbusfilemanager.worker.application;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.SchemaHistoryRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Whether this build may take work from this database.
 *
 * <p>
 * The two processes are the same jar today and need not be tomorrow: an update
 * replaces the application while a worker of the previous build is still
 * running, and the first thing the new application does is migrate. A worker
 * whose code predates a migration would go on claiming executions and writing
 * columns that mean something else now - silently, because SQL that still parses
 * is SQL that still runs.
 *
 * <p>
 * The expected version is read from the migrations packaged in this artifact,
 * never from a constant: a constant is a second place to remember, and the one
 * time nobody remembers is the one time this check was supposed to catch.
 *
 * <p>
 * A database that is behind is the ordinary startup race - the application is
 * still migrating - so it is waited for. A database that is ahead is not a race
 * at all: something newer already migrated it, and nothing this process does
 * will change that.
 */
@Slf4j
@Component
@Profile(NimbusProfiles.WORKER)
public class SchemaCompatibility {

	private static final String MIGRATIONS = "classpath*:db/migration/V*__*.sql";

	/**
	 * Bounded, because "the application is still migrating" is a state that either
	 * ends in seconds or is not what is happening. Waiting longer would keep a
	 * worker alive against a database it will never be allowed to touch.
	 */
	private static final int DEFAULT_ATTEMPTS = 10;

	private static final long DEFAULT_BETWEEN_ATTEMPTS_SECONDS = 3;

	private final SchemaHistoryRepository schemaHistoryRepository;
	private final ResourcePatternResolver resourcePatternResolver;
	private final int attempts;
	private final long betweenAttemptsMillis;

	/**
	 * The resolver is built rather than injected: it reads this artifact's own
	 * classpath, which is not a collaborator anything could substitute in
	 * production, and asking the container for one only makes the bean harder to
	 * create than the question it answers.
	 */
	@Autowired
	public SchemaCompatibility(SchemaHistoryRepository schemaHistoryRepository) {
		this(schemaHistoryRepository, new PathMatchingResourcePatternResolver(), DEFAULT_ATTEMPTS,
				TimeUnit.SECONDS.toMillis(DEFAULT_BETWEEN_ATTEMPTS_SECONDS));
	}

	/**
	 * Package-private so the waiting can be driven in milliseconds. Giving up
	 * after ten patient attempts is a decision worth asserting, and asserting it
	 * against the real interval would be half a minute of a test doing nothing.
	 */
	SchemaCompatibility(SchemaHistoryRepository schemaHistoryRepository,
			ResourcePatternResolver resourcePatternResolver, int attempts, long betweenAttemptsMillis) {
		this.schemaHistoryRepository = schemaHistoryRepository;
		this.resourcePatternResolver = resourcePatternResolver;
		this.attempts = attempts;
		this.betweenAttemptsMillis = betweenAttemptsMillis;
	}

	/**
	 * Answers once, before anything claims.
	 *
	 * @return true when this build may run work from this database
	 */
	public boolean isCompatible() {
		String expected = expectedVersion();

		for (int attempt = 1; attempt <= attempts; attempt++) {
			Optional<Boolean> verdict = verdict(expected, attempt);

			if (verdict.isPresent()) {
				return verdict.get();
			}

			if (!waitBeforeAskingAgain()) {
				return false;
			}
		}

		log.error("Gave up waiting for the database to reach schema {}", expected);

		return false;
	}

	/**
	 * @return the answer, or empty when it is still worth asking again
	 */
	private Optional<Boolean> verdict(String expected, int attempt) {
		Optional<String> current;

		try {
			current = schemaHistoryRepository.currentVersion();
		} catch (DataAccessException _) {
			// The application starts the database it supervises, and a worker launched
			// beside it can arrive first. Not knowing yet is not an answer.
			log.info("The database did not answer which schema it has (attempt {} of {})", attempt, attempts);

			return Optional.empty();
		}

		if (current.isEmpty()) {
			log.info("The database has no schema yet (attempt {} of {})", attempt, attempts);

			return Optional.empty();
		}

		return verdictFor(expected, current.get(), attempt);
	}

	private Optional<Boolean> verdictFor(String expected, String current, int attempt) {
		int difference = SchemaVersions.compare(current, expected);

		if (difference == 0) {
			log.info("Schema {} matches this build", current);

			return Optional.of(true);
		}

		if (difference > 0) {
			log.error("The database is at schema {} and this build was made for {}: a newer version migrated it, "
					+ "and this worker will not claim from it", current, expected);

			return Optional.of(false);
		}

		log.info("The database is at schema {} and this build expects {} (attempt {} of {})", current, expected,
				attempt, attempts);

		return Optional.empty();
	}

	/** @return false when the wait was interrupted, which is a reason to stop */
	private boolean waitBeforeAskingAgain() {
		try {
			TimeUnit.MILLISECONDS.sleep(betweenAttemptsMillis);

			return true;
		} catch (InterruptedException _) {
			Thread.currentThread().interrupt();

			return false;
		}
	}

	/**
	 * The highest migration this artifact carries. Package-private so a test can
	 * assert it against the migrations actually on the classpath, which is the
	 * only way this stays true as they are added.
	 */
	String expectedVersion() {
		try {
			Resource[] migrations = resourcePatternResolver.getResources(MIGRATIONS);

			return Arrays.stream(migrations).map(Resource::getFilename).filter(Objects::nonNull)
					.map(SchemaVersions::versionOf).filter(Objects::nonNull).max(SchemaVersions::compare)
					.orElseThrow(() -> new IllegalStateException("This artifact carries no migrations to expect"));
		} catch (IOException exception) {
			throw new IllegalStateException("Could not read the migrations packaged in this artifact", exception);
		}
	}
}