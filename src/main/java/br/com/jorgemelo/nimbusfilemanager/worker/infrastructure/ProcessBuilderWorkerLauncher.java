package br.com.jorgemelo.nimbusfilemanager.worker.infrastructure;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.NimbusFileManagerApplication;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerProcessLauncher;
import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerProperties;
import br.com.jorgemelo.nimbusfilemanager.worker.application.constants.WorkerConstants;

/**
 * Builds and starts the worker's command line.
 *
 * <p>
 * The same jar, started with the worker profile - which is the whole of what
 * makes the second process a worker. Two settings come as arguments rather than
 * from {@code application-worker.properties}: a profile group activates its
 * members after itself, so anything written there would follow into
 * app-worker-combined and leave the combined process headless and without a
 * schema.
 *
 * <p>
 * {@code ProcessBuilder} on the java binary directly, never through a shell.
 * The handle has to be the worker's own, or the pid, {@code onExit} and
 * {@code destroy} would all refer to a shell that exited immediately.
 *
 * <p>
 * Everything it starts comes from this installation: the java binary under
 * {@code java.home}, which for an installed copy is the runtime jpackage
 * bundled, and the jar this very class was loaded from. Nothing is looked up on
 * the PATH - a machine with some other JDK first on it would otherwise decide
 * which Java the worker runs on.
 *
 * <p>
 * The jar is resolved to an absolute path rather than taken from
 * {@code java.class.path}, because that property is relative to the working
 * directory when a jar is launched with {@code -jar}, and the worker inherits a
 * working directory nobody chose. Outside a jar - an IDE, the test suite - there
 * is no jar to point at, so the classpath is passed through as it is.
 *
 * <p>
 * {@code --enable-native-access} is deliberately not repeated here. It exists
 * for the folder watcher's FFM calls into kernel32, and the watcher belongs to
 * the application role; a worker makes no restricted native access, and passing
 * the flag anyway would grant something nothing asks for.
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
	 * The command line that turns this jar into a worker. Package-private so the
	 * arguments can be asserted without starting a JVM: they are what makes the
	 * second process a worker, and getting one wrong would give a second
	 * application quietly claiming nothing.
	 */
	List<String> command() {
		List<String> command = new ArrayList<>();

		command.add(javaBinary());

		// The worker's own heap, decided here because a process's heap can only be
		// chosen as it starts. This is the budget the split exists to give it - larger
		// than the application's, and independent of it.
		command.add("-Xms" + workerProperties.initialHeapOrDefault());
		command.add("-Xmx" + workerProperties.maxHeapOrDefault());

		installedJar().ifPresentOrElse(jar -> {
			command.add("-jar");
			command.add(jar.toString());
		}, () -> {
			command.add("-cp");
			command.add(System.getProperty("java.class.path"));
			command.add(NimbusFileManagerApplication.class.getName());
		});

		command.add("--spring.profiles.active=" + NimbusProfiles.WORKER);
		command.add("--spring.main.web-application-type=none");
		command.add("--spring.flyway.enabled=false");

		// Who to outlive. An ordered shutdown asks the worker to stop, but a kill or a
		// crash never gets to ask, and what would be left is a worker running for a
		// product nobody has open.
		command.add("--" + WorkerConstants.PARENT_PID_PROPERTY + "=" + ProcessHandle.current().pid());

		return command;
	}

	/**
	 * The jar this class was loaded from, when there is one.
	 *
	 * <p>
	 * Empty in an IDE or the test suite, where the code is a directory of classes
	 * and the classpath is what has to be handed over instead.
	 */
	Optional<Path> installedJar() {
		try {
			CodeSource source = NimbusFileManagerApplication.class.getProtectionDomain().getCodeSource();

			if (source == null) {
				return Optional.empty();
			}

			Path location = Path.of(source.getLocation().toURI());

			return location.toString().endsWith(".jar") ? Optional.of(location.toAbsolutePath()) : Optional.empty();
		} catch (URISyntaxException _) {
			// A location that is not a plain path is not a jar this can start from.
			return Optional.empty();
		}
	}

	private String javaBinary() {
		Path java = Path.of(System.getProperty(JAVA_HOME), "bin", "java");

		return java.toFile().exists() ? java.toString() : java + ".exe";
	}
}