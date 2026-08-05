package br.com.jorgemelo.nimbusfilemanager.shared.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The role decision that happens before Spring, and therefore before any test
 * of bean composition could reach it.
 *
 * <p>
 * The case that matters most is {@code app-worker-combined}: it contains the
 * word "worker" and is not one, so any answer built on a substring gets it
 * wrong and a developer running both roles from the IDE loses the tray. Every
 * other case here exists so the rule stays the one the resolved environment
 * asks elsewhere - named as a worker, not named as an application.
 */
class StartupRoleTest {

	@Test
	void readsTheProfileTheSupervisorPasses() {
		assertThat(StartupRole.isStandaloneWorker(new String[] { "--spring.profiles.active=worker" }, null)).isTrue();
	}

	@Test
	void doesNotMistakeTheCombinedProfileForAWorker() {
		assertThat(StartupRole.isStandaloneWorker(new String[] { "--spring.profiles.active=app-worker-combined" },
				null)).isFalse();
	}

	@Test
	void doesNotMistakeTheApplicationForAWorker() {
		assertThat(StartupRole.isStandaloneWorker(new String[] { "--spring.profiles.active=app" }, null)).isFalse();
	}

	/**
	 * An installed copy, the MSI and every existing launcher start the jar with no
	 * profile argument at all, and they must keep getting the application.
	 */
	@Test
	void treatsAnUnqualifiedStartAsTheApplication() {
		assertThat(StartupRole.isStandaloneWorker(new String[0], null)).isFalse();
	}

	@Test
	void treatsABlankProfileAsTheApplication() {
		assertThat(StartupRole.isStandaloneWorker(new String[] { "--spring.profiles.active=   " }, null)).isFalse();
	}

	/** A worker started by hand configures itself the way Spring also allows. */
	@Test
	void readsTheSystemPropertyWhenThereIsNoArgument() {
		assertThat(StartupRole.isStandaloneWorker(new String[] { "--server.port=0" }, "worker")).isTrue();
	}

	@Test
	void prefersTheArgumentOverTheSystemProperty() {
		assertThat(StartupRole.isStandaloneWorker(new String[] { "--spring.profiles.active=app" }, "worker")).isFalse();

		assertThat(StartupRole.isStandaloneWorker(new String[] { "--spring.profiles.active=worker" }, "app")).isTrue();
	}

	/**
	 * Both roles named explicitly is the combined process spelled out, and it is
	 * an application as much as the group is.
	 */
	@Test
	void isNotAWorkerWhenTheApplicationIsNamedBesideIt() {
		assertThat(StartupRole.isStandaloneWorker(new String[] { "--spring.profiles.active=worker,app" }, null))
				.isFalse();
	}

	@Test
	void staysAWorkerAlongsideProfilesThatNameNoRole() {
		assertThat(StartupRole.isStandaloneWorker(new String[] { "--spring.profiles.active= worker , diagnostics " },
				null)).isTrue();
	}

	/**
	 * A list written by hand, or built by concatenation, ends up with a stray
	 * separator often enough that Spring itself ignores one.
	 */
	@Test
	void ignoresAnEmptyNameLeftByATrailingComma() {
		assertThat(StartupRole.isStandaloneWorker(new String[] { "--spring.profiles.active=worker," }, null)).isTrue();
	}

	@Test
	void readsTheProfileNameWhateverItsCase() {
		assertThat(StartupRole.isStandaloneWorker(new String[] { "--spring.profiles.active=WORKER" }, null)).isTrue();
	}

	/** Spring lets the last one win, and so does this. */
	@Test
	void letsTheLastProfileArgumentWin() {
		assertThat(StartupRole.isStandaloneWorker(
				new String[] { "--spring.profiles.active=worker", "--spring.profiles.active=app" }, null)).isFalse();
	}

	/**
	 * The entry point {@code main} actually calls. It reads the ambient system
	 * property, so the only thing worth asserting through it is that the argument
	 * alone decides - which is the case the supervisor relies on.
	 */
	@Test
	void answersTheSameThroughThePublicEntryPoint() {
		assertThat(StartupRole.isStandaloneWorker(new String[] { "--spring.profiles.active=worker" })).isTrue();

		assertThat(StartupRole.isStandaloneWorker(new String[] { "--spring.profiles.active=app-worker-combined" }))
				.isFalse();
	}
}