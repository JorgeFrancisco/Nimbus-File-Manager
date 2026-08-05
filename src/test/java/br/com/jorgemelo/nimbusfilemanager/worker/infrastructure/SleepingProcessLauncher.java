package br.com.jorgemelo.nimbusfilemanager.worker.infrastructure;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerProcessLauncher;

/**
 * Starts a JVM that sleeps, the same way the real launcher starts a worker:
 * {@code ProcessBuilder} straight onto the java binary, never through a shell.
 *
 * <p>
 * Remembers what it launched so a test can assert that nothing survived - the
 * orphan is the failure the packaging must never see, and it is only visible by
 * looking at the processes afterwards.
 */
final class SleepingProcessLauncher implements WorkerProcessLauncher {

	private final List<Process> launched = new ArrayList<>();

	@Override
	public Process launch() throws IOException {
		Process process = new ProcessBuilder(javaBinary(), "-cp", System.getProperty("java.class.path"),
				SleepingProcess.class.getName()).start();

		launched.add(process);

		return process;
	}

	Process lastStarted() {
		return launched.getLast();
	}

	List<Process> everyStarted() {
		return List.copyOf(launched);
	}

	private String javaBinary() {
		Path java = Path.of(System.getProperty("java.home"), "bin", "java");

		return java.toFile().exists() ? java.toString() : java + ".exe";
	}
}