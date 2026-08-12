package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import jakarta.persistence.EntityManager;

/**
 * The cheap question the daily purge asks before queueing itself, executed for
 * real.
 *
 * <p>
 * It asked with a {@code LocalDateTime} while the column it compares had become
 * an {@code Instant}. Spring Data resolves a derived query by name, so it
 * compiled; Hibernate refuses the argument at execution, so every scheduled
 * purge died on it - {@code Argument [...] of type [java.time.LocalDateTime] did
 * not match parameter type [java.time.Instant]}. Nothing missing was ever aged
 * out of the catalog, and the only trace was one line in a log nobody reads.
 *
 * <p>
 * The consumer's own test could not see it: it mocks the repository, and a mock
 * accepts any type. That is the hole this closes - the method is executed here
 * against PostgreSQL, through Hibernate, with values that decide the answer in
 * both directions.
 *
 * <p>
 * <b>The negative cases are read as a delta.</b> The question is about the whole
 * catalog rather than about one file, so asserting a bare {@code false} would be
 * asserting something about every other test's rows too. What each of them
 * proves is that the file it adds does not change the answer.
 */
class CatalogPurgeCutoffIntegrationTest extends SharedPostgresIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");
	private static final Instant CUTOFF = NOW.minus(90, ChronoUnit.DAYS);

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	/**
	 * The whole point of the question: something aged past the window, so the
	 * purge has a reason to run. This one is absolute - a file this old makes the
	 * answer true whatever else is on record.
	 */
	@Test
	void aFileMissingSinceBeforeTheCutoffIsSomethingToPurge(@TempDir Path library) {
		missingSince(library.resolve("gone.jpg"), NOW.minus(400, ChronoUnit.DAYS));

		Assertions.assertThat(anythingMissingBefore(CUTOFF)).isTrue();
	}

	/**
	 * The other side of the boundary, without which the assertion above would pass
	 * against a query that answers yes to everything.
	 */
	@Test
	void aFileMissingSinceAfterTheCutoffIsNotYetSomethingToPurge(@TempDir Path library) {
		boolean withoutIt = anythingMissingBefore(CUTOFF);

		missingSince(library.resolve("recent.jpg"), NOW.minus(10, ChronoUnit.DAYS));

		Assertions.assertThat(anythingMissingBefore(CUTOFF))
				.as("a file missing for ten days against a ninety day window").isEqualTo(withoutIt);
	}

	/**
	 * Age is not the only condition. A file catalogued years ago and still there is
	 * not missing, and the retention window has nothing to say about it.
	 */
	@Test
	void anOldFileThatIsStillThereIsNotSomethingToPurge(@TempDir Path library) {
		boolean withoutIt = anythingMissingBefore(CUTOFF);

		CatalogFile present = catalogued(library.resolve("kept.jpg"));

		jdbcTemplate.update("UPDATE catalog_file SET lifecycle_changed_at = ? WHERE id = ?",
				Timestamp.from(NOW.minus(400, ChronoUnit.DAYS)), present.getId());

		Assertions.assertThat(anythingMissingBefore(CUTOFF)).as("old, but nobody lost it").isEqualTo(withoutIt);
	}

	private boolean anythingMissingBefore(Instant cutoff) {
		entityManager.flush();

		return catalogFileRepository.existsByLifecycleStatusAndLifecycleChangedAtBefore(LifecycleStatus.MISSING,
				cutoff);
	}

	private void missingSince(Path path, Instant since) {
		CatalogFile file = catalogued(path);

		jdbcTemplate.update(
				"UPDATE catalog_file SET lifecycle_status = 'MISSING', lifecycle_changed_at = ? WHERE id = ?",
				Timestamp.from(since), file.getId());
	}

	private CatalogFile catalogued(Path path) {
		entityManager.flush();

		return CatalogFiles.catalogued(catalogFileRepository, catalogFileLocationRepository, path);
	}
}