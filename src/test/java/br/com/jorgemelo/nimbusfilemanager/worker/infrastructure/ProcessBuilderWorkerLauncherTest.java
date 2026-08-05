package br.com.jorgemelo.nimbusfilemanager.worker.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerProperties;
import br.com.jorgemelo.nimbusfilemanager.worker.application.constants.WorkerConstants;

/**
 * The command line that makes the second process a worker.
 *
 * <p>
 * Asserted rather than started, because what can go wrong here is an argument,
 * not a JVM: the profile is what makes it claim, and the two flags are what
 * keep it headless and out of the migrations. Those flags are passed on the
 * command line rather than written into the worker profile, because a profile
 * group activates its members after itself - anything set there would follow
 * into app-worker-combined and leave the combined process with no screen and no
 * schema.
 */
class ProcessBuilderWorkerLauncherTest {

	private final ProcessBuilderWorkerLauncher launcher = new ProcessBuilderWorkerLauncher(
			new WorkerProperties(null, null, null, null, null, null, null, null, null, null));

	@Test
	void startsTheSameApplicationInTheWorkerRole() {
		assertThat(launcher.command()).contains("br.com.jorgemelo.nimbusfilemanager.NimbusFileManagerApplication",
				"--spring.profiles.active=worker");
	}

	/**
	 * Who the worker has to outlive. Ordered shutdown covers the application
	 * closing properly; this covers it not getting to ask, which is the case that
	 * would otherwise leave a worker running for a product nobody has open.
	 */
	@Test
	void tellsTheWorkerWhichProcessToOutlive() {
		assertThat(launcher.command())
				.contains("--" + WorkerConstants.PARENT_PID_PROPERTY + "=" + ProcessHandle.current().pid());
	}

	@Test
	void startsItHeadlessAndWithoutMigrations() {
		assertThat(launcher.command()).contains("--spring.main.web-application-type=none",
				"--spring.flyway.enabled=false");
	}

	/**
	 * The java binary of the JVM that is running, never one found on the PATH: an
	 * installed copy ships its own runtime, and a worker started on a different
	 * Java is a class-version failure at best.
	 */
	@Test
	void usesTheJavaThisProcessIsRunningOn() {
		assertThat(launcher.command().getFirst()).startsWith(System.getProperty("java.home"));
	}

	/**
	 * Outside a jar - here, and in an IDE - there is no jar to point at, so the
	 * classpath is what gets handed over.
	 */
	@Test
	void handsTheWorkerThisProcessesClasspathWhenThereIsNoJar() {
		assertThat(launcher.installedJar()).isEmpty();
		assertThat(launcher.command()).contains("-cp", System.getProperty("java.class.path"));
	}

	/**
	 * Nothing comes from the PATH. A machine with another JDK first on it must not
	 * get to decide which Java the worker runs on - the installed copy ships its
	 * own runtime, and that is the one under java.home.
	 */
	@Test
	void takesTheJavaBinaryFromThisInstallationOnly() {
		String java = launcher.command().getFirst();

		assertThat(java).startsWith(System.getProperty("java.home")).isNotEqualTo("java").isNotEqualTo("java.exe");
	}

	/**
	 * The flag exists for the folder watcher's FFM calls, and the watcher is
	 * application-only. Passing it to a worker that makes no restricted native
	 * access would be granting something nothing asks for.
	 */
	@Test
	void doesNotGrantNativeAccessTheWorkerDoesNotNeed() {
		assertThat(launcher.command()).doesNotContain("--enable-native-access=ALL-UNNAMED");
	}

	/**
	 * The worker's own heap, which is the point of running it in a process of its
	 * own. Decided here because a heap can only be chosen as a JVM starts - a
	 * property could never change one already running.
	 */
	@Test
	void givesTheWorkerItsOwnHeapBudget() {
		assertThat(launcher.command()).contains("-Xms" + WorkerProperties.DEFAULT_INITIAL_HEAP,
				"-Xmx" + WorkerProperties.DEFAULT_MAX_HEAP);
	}

	@Test
	void usesTheConfiguredHeapWhenOneIsGiven() {
		ProcessBuilderWorkerLauncher configured = new ProcessBuilderWorkerLauncher(
				new WorkerProperties(null, null, null, null, null, null, null, "128m", "512m", null));

		assertThat(configured.command()).contains("-Xms128m", "-Xmx512m");
	}
}