package br.com.jorgemelo.nimbusfilemanager.shared;

import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The names the suite's containers carry into Docker.
 *
 * <p>
 * They exist to be read by a person looking at a list of running containers, and
 * two properties decide whether they are worth anything there: every one has to
 * say which test it belongs to, and no two may collide - Docker refuses a name
 * already in use, and this suite runs classes concurrently.
 *
 * <p>
 * The classes named below are real ones from this suite rather than stand-ins,
 * so the three shapes being checked are the three that actually reach the
 * helper.
 */
class TestPostgresTest {

	/** What Docker accepts: the first character is stricter than the rest. */
	private static final Pattern DOCKER_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]*");

	@Test
	void namesCarryTheSuitePrefixSoTheyGroupTogether() {
		Assertions.assertThat(TestPostgres.nameFor(SharedPostgresIntegrationTest.class))
				.startsWith("nimbus-it-SharedPostgres-");
	}

	/**
	 * The part every class repeats is dropped. A list where each entry ends in
	 * {@code IntegrationTest} spends its width saying what the prefix already said.
	 */
	@Test
	void theRepeatedSuffixesAreLeftOutOfTheName() {
		Assertions.assertThat(TestPostgres.nameFor(SharedPostgresIntegrationTest.class))
				.doesNotContain("IntegrationTest");

		Assertions.assertThat(TestPostgres.nameFor(TestPostgresTest.class)).startsWith("nimbus-it-TestPostgres-")
				.doesNotContain("TestTest");
	}

	/** A class with neither suffix keeps its name whole. */
	@Test
	void aClassWithNoSuffixToDropKeepsItsNameWhole() {
		Assertions.assertThat(TestPostgres.nameFor(CatalogFiles.class)).startsWith("nimbus-it-CatalogFiles-");
	}

	/**
	 * A class named after nothing but the suffix would trim away to an empty name,
	 * and {@code nimbus-it--7d21a4e0} identifies less than the class does.
	 */
	@Test
	void aClassWhoseNameIsOnlyASuffixKeepsIt() {
		Assertions.assertThat(TestPostgres.nameFor(Test.class)).startsWith("nimbus-it-Test-");
	}

	/**
	 * The reason the tail exists. Two runs of one class - a rerun started while the
	 * first is shutting down, two branches building at once - would otherwise ask
	 * Docker for a name it already holds, and the failure would name the container
	 * rather than the cause.
	 */
	@Test
	void twoNamesForTheSameClassNeverCollide() {
		long distinct = IntStream.range(0, 500)
				.mapToObj(_ -> TestPostgres.nameFor(SharedPostgresIntegrationTest.class)).distinct().count();

		Assertions.assertThat(distinct).isEqualTo(500);
	}

	@Test
	void everyNameIsOneDockerAccepts() {
		for (Class<?> testClass : new Class<?>[] { SharedPostgresIntegrationTest.class, TestPostgresTest.class,
				CatalogFiles.class, Test.class }) {
			Assertions.assertThat(TestPostgres.nameFor(testClass)).as(testClass.getSimpleName()).matches(DOCKER_NAME);
		}
	}
}