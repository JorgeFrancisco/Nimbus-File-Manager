package br.com.jorgemelo.nimbusfilemanager.worker.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Versions compared as versions.
 *
 * <p>
 * The case worth the class is nine against ten: as text, "9" is the larger, and
 * a worker believing that would decide it was ahead of a database that is ahead
 * of it - and then claim work against a schema it does not understand.
 */
class SchemaVersionsTest {

	@Test
	void readsTheVersionOutOfAMigrationName() {
		assertThat(SchemaVersions.versionOf("V16__execution_becomes_the_work_queue.sql")).isEqualTo("16");
	}

	@Test
	void ignoresAFileThatIsNotAMigration() {
		assertThat(SchemaVersions.versionOf("afterMigrate.sql")).isNull();
	}

	@Test
	void ordersByNumberAndNotByText() {
		assertThat(SchemaVersions.compare("9", "10")).isNegative();
		assertThat(SchemaVersions.compare("10", "9")).isPositive();
	}

	@Test
	void treatsTheSameVersionAsTheSame() {
		assertThat(SchemaVersions.compare("18", "18")).isZero();
	}

	@Test
	void comparesEachPartOfADottedVersion() {
		assertThat(SchemaVersions.compare("1.2", "1.10")).isNegative();
	}

	/** Flyway means the same thing by both, so this has to as well. */
	@Test
	void treatsAMissingPartAsZero() {
		assertThat(SchemaVersions.compare("1", "1.0")).isZero();
	}
}