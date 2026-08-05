package br.com.jorgemelo.nimbusfilemanager.worker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.dao.CannotAcquireLockException;

import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.SchemaHistoryRepository;

/**
 * Whether this build may claim from this database.
 *
 * <p>
 * Two situations look alike from here and must not be treated alike. A database
 * behind this build is the ordinary startup race, and waiting is the answer. A
 * database ahead of it is an update that already happened, and waiting would
 * only delay the same refusal - the worker is the old binary, and no amount of
 * patience makes it the new one.
 */
class SchemaCompatibilityTest {

	private static final int ATTEMPTS = 3;

	private final SchemaHistoryRepository repository = mock(SchemaHistoryRepository.class);

	private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

	@Test
	void runsWhenTheDatabaseIsTheSchemaThisBuildExpects() {
		SchemaCompatibility compatibility = compatibility();

		when(repository.currentVersion()).thenReturn(Optional.of(compatibility.expectedVersion()));

		assertThat(compatibility.isCompatible()).isTrue();
	}

	/**
	 * The upgrade case: a newer application migrated the database while this
	 * worker, of the previous build, was still running. Refused at once rather
	 * than waited for - nothing about waiting changes which binary this is.
	 */
	@Test
	void refusesADatabaseThatIsAheadOfThisBuildWithoutWaiting() {
		SchemaCompatibility compatibility = compatibility();

		when(repository.currentVersion()).thenReturn(Optional.of(aheadOf(compatibility.expectedVersion())));

		assertThat(compatibility.isCompatible()).isFalse();

		verify(repository, times(1)).currentVersion();
	}

	@Test
	void waitsForADatabaseThatIsBehindAndThenGivesUp() {
		SchemaCompatibility compatibility = compatibility();

		when(repository.currentVersion()).thenReturn(Optional.of("1"));

		assertThat(compatibility.isCompatible()).isFalse();

		verify(repository, times(ATTEMPTS)).currentVersion();
	}

	/**
	 * The application starts the database it supervises, and a worker launched
	 * beside it can ask before there is anything to answer.
	 */
	@Test
	void waitsWhileTheDatabaseCannotAnswer() {
		SchemaCompatibility compatibility = compatibility();

		when(repository.currentVersion()).thenThrow(new CannotAcquireLockException("not up yet"));

		assertThat(compatibility.isCompatible()).isFalse();

		verify(repository, times(ATTEMPTS)).currentVersion();
	}

	@Test
	void waitsWhileTheDatabaseHasNoSchemaAtAll() {
		SchemaCompatibility compatibility = compatibility();

		when(repository.currentVersion()).thenReturn(Optional.empty());

		assertThat(compatibility.isCompatible()).isFalse();

		verify(repository, times(ATTEMPTS)).currentVersion();
	}

	/**
	 * A database that catches up while the worker waits is the whole reason the
	 * waiting exists.
	 */
	@Test
	void runsWhenTheDatabaseCatchesUpWhileItWaits() {
		SchemaCompatibility compatibility = compatibility();

		when(repository.currentVersion()).thenReturn(Optional.of("1"))
				.thenReturn(Optional.of(compatibility.expectedVersion()));

		assertThat(compatibility.isCompatible()).isTrue();
	}

	/**
	 * The expected version comes from the migrations on the classpath, so this
	 * asserts the mechanism rather than a number: naming one here would be a
	 * second place to remember, which is exactly what the check exists to avoid.
	 */
	@Test
	void expectsTheHighestMigrationThisArtifactCarries() throws IOException {
		Resource[] migrations = resolver.getResources("classpath*:db/migration/V*__*.sql");

		String highest = Arrays.stream(migrations).map(Resource::getFilename).filter(Objects::nonNull)
				.map(SchemaVersions::versionOf).filter(Objects::nonNull).max(SchemaVersions::compare).orElseThrow();

		assertThat(compatibility().expectedVersion()).isEqualTo(highest);
	}

	private SchemaCompatibility compatibility() {
		return new SchemaCompatibility(repository, resolver, ATTEMPTS, 1);
	}

	private String aheadOf(String version) {
		return String.valueOf(Long.parseLong(version.split("\\.")[0]) + 1);
	}
}