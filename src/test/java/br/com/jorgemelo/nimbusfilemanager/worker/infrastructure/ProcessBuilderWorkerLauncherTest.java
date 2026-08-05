package br.com.jorgemelo.nimbusfilemanager.worker.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.shared.application.WorkspaceLocation;
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
	 * Outside an installed image - here, and in an IDE - there is no launcher to
	 * start, so the classpath is what gets handed over.
	 */
	@Test
	void handsTheWorkerThisProcessesClasspathWhenThereIsNoInstalledLauncher() {
		assertThat(launcher.installedWorkerLauncher()).isEmpty();
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

	/**
	 * An installed copy is recognised by a file being there, next to the runtime -
	 * not by where this class was loaded from. Asking that older question inside
	 * the packaged jar threw as the application started, because the answer is a
	 * URI into a nested archive rather than a path.
	 */
	@Test
	void recognisesAnInstalledImageByTheWorkerLauncherBeingInIt(@TempDir Path imageRoot) throws IOException {
		assertThat(launcher.workerLauncherIn(imageRoot)).as("a folder that is not an installed image").isEmpty();

		Path workerLauncher = imageRoot.resolve(WorkerConstants.WORKER_LAUNCHER);

		Files.createFile(workerLauncher);

		assertThat(launcher.workerLauncherIn(imageRoot)).contains(workerLauncher);
	}

	/**
	 * The installed worker is started through its own launcher and told only what
	 * cannot be built in: the pid to outlive. Its profile, its headless start, its
	 * skipped migrations and its heap live in that launcher's configuration.
	 *
	 * <p>
	 * No java binary and no classpath, and that is the point rather than an
	 * omission: an image packaged by jpackage has no java launcher in its runtime,
	 * so anything of that shape could only find some other Java on the machine.
	 */
	@Test
	void startsTheInstalledWorkerThroughItsOwnLauncherAndNothingElse(@TempDir Path imageRoot) {
		Path workerLauncher = imageRoot.resolve(WorkerConstants.WORKER_LAUNCHER);

		assertThat(launcher.installedCommand(workerLauncher)).containsExactly(workerLauncher.toString(),
				WorkspaceLocation.argumentFor(WorkspaceLocation.resolve()),
				"--" + WorkerConstants.PARENT_PID_PROPERTY + "=" + ProcessHandle.current().pid());
	}

	/**
	 * Every worker is told which workspace to work in, both ways of starting one.
	 *
	 * <p>
	 * Not left to be inherited: an application restarted with administrator rights
	 * is given a fresh environment, so the variable that chose the workspace is
	 * gone by then and the answer lives in a system property - which a new JVM does
	 * not inherit either. Implicit, a worker would resolve the default under the
	 * user's home while the application worked somewhere else - on the first start
	 * and on every restart alike.
	 */
	@Test
	void tellsEveryWorkerWhichWorkspaceToWorkIn(@TempDir Path imageRoot) {
		String workspace = WorkspaceLocation.argumentFor(WorkspaceLocation.resolve());

		assertThat(launcher.developmentCommand()).contains(workspace);
		assertThat(launcher.installedCommand(imageRoot.resolve(WorkerConstants.WORKER_LAUNCHER)))
			.contains(workspace);
	}
}