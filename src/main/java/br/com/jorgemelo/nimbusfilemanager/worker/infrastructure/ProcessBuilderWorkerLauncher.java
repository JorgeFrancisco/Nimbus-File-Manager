package br.com.jorgemelo.nimbusfilemanager.worker.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.NimbusFileManagerApplication;
import br.com.jorgemelo.nimbusfilemanager.shared.application.WorkspaceLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerProcessLauncher;
import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerProperties;
import br.com.jorgemelo.nimbusfilemanager.worker.application.constants.WorkerConstants;

/**
 * Builds and starts the worker's command line.
 *
 * <p>
 * Two ways to start the same worker, because there are two ways this code is
 * packaged. An installed copy has a launcher of its own, built into the image
 * beside the application's, carrying the profile and the heap in its
 * configuration; a development run has a directory of classes and a JVM to point
 * at it. The worker that comes out is the same either way - one process, the
 * worker profile, the workspace it inherits - so the difference is packaging and
 * nothing else.
 *
 * <p>
 * Which of the two is decided by whether the worker's launcher is there, next to
 * the runtime this process is running on. That is a file that exists or does
 * not. It used to be decided by asking where this class had been loaded from,
 * and inside the packaged jar that question has no answer of the kind the code
 * expected: the location is a URI into a nested archive, which threw as the
 * application started and left the installed product showing a failure dialog
 * and nothing else.
 *
 * <p>
 * {@code ProcessBuilder} on the executable directly, never through a shell. The
 * handle has to be the worker's own, or the pid, {@code onExit} and
 * {@code destroy} would all refer to a shell that exited immediately. Arguments
 * go across as a list, so a path with spaces - and the installed one has two -
 * needs no quoting and cannot be split.
 *
 * <p>
 * Nothing is looked up on the PATH, and the installed path has no fallback to a
 * {@code java} binary: an image packaged by jpackage has no launcher in its
 * runtime at all, so a fallback there could only find some other Java on the
 * machine, which is the one thing an installed copy must never depend on.
 */
@Component
@Profile(NimbusProfiles.APP + " & !" + NimbusProfiles.WORKER)
public class ProcessBuilderWorkerLauncher implements WorkerProcessLauncher {

	private final WorkerProperties workerProperties;

	public ProcessBuilderWorkerLauncher(WorkerProperties workerProperties) {
		this.workerProperties = workerProperties;
	}

	private static final String JAVA_HOME = "java.home";

	@Override
	public Process launch() throws IOException {
		ProcessBuilder builder = new ProcessBuilder(command());

		builder.inheritIO();

		return builder.start();
	}

	/**
	 * The command line that starts the worker, whichever way this copy is
	 * packaged. Package-private so both shapes can be asserted without starting a
	 * JVM: they are what makes the second process a worker, and getting one wrong
	 * would give a second application quietly claiming nothing.
	 */
	List<String> command() {
		return installedWorkerLauncher().map(this::installedCommand).orElseGet(this::developmentCommand);
	}

	/**
	 * The installed worker takes almost nothing from here.
	 *
	 * <p>
	 * Its profile, its headless start, its skipped migrations and its heap are all
	 * in the launcher's own configuration, written once when the image was built.
	 * Only the pid cannot be: it is a different number every run.
	 */
	List<String> installedCommand(Path workerLauncher) {
		return List.of(workerLauncher.toString(), workspace(), parentPid());
	}

	/**
	 * Outside an installed image the worker is this same jar - or these same
	 * classes - started again with the worker profile, on the JVM this process is
	 * running on and with the classpath it was given.
	 *
	 * <p>
	 * Two settings come as arguments rather than from
	 * {@code application-worker.properties}: a profile group activates its members
	 * after itself, so anything written there would follow into
	 * app-worker-combined and leave the combined process headless and without a
	 * schema.
	 *
	 * <p>
	 * {@code --enable-native-access} is deliberately not repeated. It exists for
	 * the folder watcher's FFM calls into kernel32, and the watcher belongs to the
	 * application role; a worker makes no restricted native access, and passing the
	 * flag anyway would grant something nothing asks for.
	 */
	List<String> developmentCommand() {
		List<String> command = new ArrayList<>();

		command.add(javaBinary());

		// The worker's own heap, decided here because a process's heap can only be
		// chosen as it starts. This is the budget the split exists to give it - larger
		// than the application's, and independent of it.
		command.add("-Xms" + workerProperties.initialHeapOrDefault());
		command.add("-Xmx" + workerProperties.maxHeapOrDefault());

		command.add("-cp");
		command.add(System.getProperty("java.class.path"));
		command.add(NimbusFileManagerApplication.class.getName());

		command.add("--spring.profiles.active=" + NimbusProfiles.WORKER);
		command.add("--spring.main.web-application-type=none");
		command.add("--spring.flyway.enabled=false");

		command.add(workspace());
		command.add(parentPid());

		return command;
	}

	/**
	 * The workspace this application settled on, told to the worker rather than
	 * left to be worked out again.
	 *
	 * <p>
	 * A child process inherits an environment, and this one cannot be trusted to
	 * still carry the variable that chose the workspace: an elevated restart is
	 * given a fresh environment by Windows, so an application that came up that way
	 * holds the answer as a system property - and a system property is not
	 * inherited by a new JVM either. Left implicit, every worker would quietly
	 * resolve the default under the user's home while the application worked
	 * somewhere else, on a restart exactly as on the first start.
	 *
	 * <p>
	 * The same argument the elevated restart is given, read by the same line of
	 * {@code main}, and resolved by the same order of preference. One form, one
	 * place it is understood.
	 */
	private String workspace() {
		return WorkspaceLocation.argumentFor(WorkspaceLocation.resolve());
	}

	/**
	 * The worker's launcher in the image this process is running from, when this
	 * process is running from one.
	 *
	 * <p>
	 * {@code java.home} in an installed copy is the runtime folder inside the
	 * image, so its parent is the image itself - the folder holding both
	 * launchers, the jar and that runtime. Elsewhere the parent is a JDK's own
	 * folder, where no such file exists, which is the answer wanted there.
	 */
	Optional<Path> installedWorkerLauncher() {
		return workerLauncherIn(Path.of(System.getProperty(JAVA_HOME)).getParent());
	}

	Optional<Path> workerLauncherIn(Path imageRoot) {
		Path launcher = imageRoot.resolve(WorkerConstants.WORKER_LAUNCHER);

		return Files.isRegularFile(launcher) ? Optional.of(launcher) : Optional.empty();
	}

	/**
	 * Who to outlive. An ordered shutdown asks the worker to stop, but a kill or a
	 * crash never gets to ask, and what would be left is a worker running for a
	 * product nobody has open.
	 */
	private String parentPid() {
		return "--" + WorkerConstants.PARENT_PID_PROPERTY + "=" + ProcessHandle.current().pid();
	}

	private String javaBinary() {
		Path java = Path.of(System.getProperty(JAVA_HOME), "bin", "java");

		return java.toFile().exists() ? java.toString() : java + ".exe";
	}
}