package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The production logging configuration, which no other test can reach.
 *
 * <p>
 * The suite runs on a configuration of its own, named explicitly by Surefire so
 * that the application's is never in the running - it must not append to the log
 * file of the installation on the same machine. So the real configuration is not
 * exercised by running anything, and what is left is to read it.
 *
 * <p>
 * It is read for the ways it goes wrong <em>silently</em>, which is the only
 * kind worth a test here: every line printed twice, a line labelled with a role
 * that is not its own, and - the one that actually happened - a file in the test
 * resources whose name Logback picks up by itself, taking the application's
 * configuration away from it wherever both land on one classpath.
 */
class LogbackConfigurationTest {

	private static final Path CONFIGURATION = Path.of("src/main/resources/logback-spring.xml");

	/**
	 * Boot's own console appender is not included. Including it as well as defining
	 * one here would leave two console appenders in the context, and a root
	 * referring to both prints everything twice - which reads as the application
	 * looping.
	 */
	@Test
	void definesExactlyOneConsoleAppenderAndDoesNotIncludeBootsOwn() throws Exception {
		String configuration = Files.readString(CONFIGURATION);

		Assertions.assertThat(configuration).doesNotContain("logback/console-appender.xml")
				.containsOnlyOnce("<appender name=\"CONSOLE\"").containsOnlyOnce("<appender-ref ref=\"CONSOLE\"/>");
	}

	/**
	 * One file per role, because two JVMs sharing one rolling file rename it out
	 * from under each other. The name has to carry the role, and the archive
	 * pattern has to carry it too - otherwise the two processes would collide again
	 * the first time the file rolls.
	 */
	@Test
	void writesToAFileNamedAfterTheRoleAndRollsToArchivesThatKeepIt() throws Exception {
		String configuration = Files.readString(CONFIGURATION);

		Assertions.assertThat(configuration).contains("nimbus-file-manager-${LOG_ROLE}.log")
				.contains("nimbus-file-manager-${LOG_ROLE}-%d{yyyyMMdd}.%i.log");
	}

	/**
	 * Every role a process can start in resolves both properties. A profile
	 * expression that matched nothing would leave them undefined, and Logback would
	 * write the literal {@code LOG_ROLE_IS_UNDEFINED} into the file name.
	 */
	@ParameterizedTest
	@CsvSource({ "app-worker-combined, combined, COMBINED", "worker &amp; !app-worker-combined, worker, WORKER",
			"!worker, app, APP" })
	void everyRoleResolvesItsFileNameAndItsFallbackToken(String profile, String role, String token) throws Exception {
		String configuration = Files.readString(CONFIGURATION);

		Assertions.assertThat(configuration).contains("<springProfile name=\"" + profile + "\">")
				.contains("<property name=\"LOG_ROLE\" value=\"" + role + "\"/>")
				.contains("<property name=\"LOG_ROLE_TOKEN\" value=\"" + token + "\"/>");
	}

	/**
	 * The file carries the role exactly where its name cannot.
	 *
	 * <p>
	 * A process with one role writes a file whose every line has that role, and the
	 * name says so - a column repeating it would be noise. The combined run is the
	 * one that breaks that reasoning: one file, both roles inside, and a name that
	 * says "combined" answers nothing about any single line.
	 */
	@Test
	void putsTheRoleInTheFileOnlyForTheRunWhoseNameCannotSayIt() throws Exception {
		String configuration = Files.readString(CONFIGURATION);

		Assertions.assertThat(configuration).contains("<pattern>${FILE_PATTERN}</pattern>")
				.contains("<property name=\"FILE_PATTERN\" value=\"${FILE_LOG_PATTERN}\"/>")
				.contains("<property name=\"FILE_PATTERN\""
						+ " value=\"[%-8X{nimbusRole:-COMBINED}] ${FILE_LOG_PATTERN}\"/>");
	}

	/**
	 * Nothing in the test resources carries a name Logback picks up by itself.
	 *
	 * <p>
	 * This one cost an afternoon. Logback loads {@code logback-test.xml} from
	 * anywhere on the classpath, before Spring Boot gets to choose - so the file
	 * that keeps the suite from writing to the installation's log also shadowed the
	 * application's own configuration whenever {@code target/test-classes} was on
	 * the classpath of a running application. Which is what an IDE does when it
	 * launches one: the application started with the test configuration, wrote no
	 * log file at all, and said nothing about why.
	 *
	 * <p>
	 * The test configuration therefore has a name nobody looks for on their own,
	 * and Surefire points at it. Putting a standard name back here would return the
	 * defect silently, so it fails here instead.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "logback-test.xml", "logback.xml", "logback-test.groovy", "logback.groovy",
			"logback-test-spring.xml", "logback-spring.xml" })
	void noTestResourceUsesANameLogbackOrBootLoadsByItself(String name) {
		Assertions.assertThat(Path.of("src/test/resources", name))
				.as("A configuration named %s in the test resources is found on its own and shadows the"
						+ " application's logback-spring.xml wherever both are on one classpath.", name)
				.doesNotExist();
	}

	/**
	 * And the suite's configuration is reached the only way left: by being named.
	 */
	@Test
	void surefirePointsAtTheTestConfigurationExplicitly() throws Exception {
		Assertions.assertThat(Files.readString(Path.of("pom.xml")))
				.contains("<logging.config>classpath:logback-tests.xml</logging.config>");

		Assertions.assertThat(Path.of("src/test/resources/logback-tests.xml")).exists();
	}

	/**
	 * The fallback is what a line gets when nothing marked it, and the marker is
	 * what {@code LoggingRole} writes. If the key here and the key there ever stop
	 * matching, every line silently returns to the fallback and the worker's lines
	 * in a combined run become indistinguishable again.
	 */
	@Test
	void readsTheRoleFromTheKeyTheApplicationWrites() throws Exception {
		String configuration = Files.readString(CONFIGURATION);

		Assertions.assertThat(configuration).contains("X{nimbusRole:-${LOG_ROLE_TOKEN}}");
	}
}