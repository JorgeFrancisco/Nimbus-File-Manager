package br.com.jorgemelo.nimbusfilemanager.worker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.worker.application.constants.WorkerExitCodes;

/**
 * Outliving the application, against real processes.
 *
 * <p>
 * The property is about a process that is not this one, so a mocked
 * {@code ProcessHandle} would prove only that a method was called. What can
 * actually go wrong is a pid that means nothing, or an {@code onExit} that
 * never fires - and both need a real process to happen to.
 *
 * <p>
 * The child is a JVM that waits rather than the application itself: starting
 * Nimbus would need a database and would be testing Nimbus. What is under test
 * is whether a worker notices the process it was told to outlive.
 */
class ParentProcessWatchTest {

	private final WorkerStandDown standDown = mock(WorkerStandDown.class);

	private Process parent;

	@AfterEach
	void killWhateverSurvived() {
		if (parent != null) {
			parent.destroyForcibly();
		}
	}

	@Test
	void watchesNobodyWhenNobodyStartedIt() {
		watch(null).watchParent();

		verify(standDown, never()).leave(anyString(), anyInt());
	}

	/**
	 * The application can die between starting this process and this process
	 * getting here - the same situation as an exit, only already over.
	 */
	@Test
	void leavesAtOnceWhenTheApplicationIsAlreadyGone() {
		long neverStarted = Long.MAX_VALUE;

		watch(neverStarted).watchParent();

		verify(standDown).leave(anyString(), anyInt());
	}

	@Test
	void staysWhileTheApplicationIsAlive() throws IOException {
		parent = sleepingJvm();

		watch(parent.pid()).watchParent();

		verify(standDown, never()).leave(anyString(), anyInt());
	}

	/**
	 * The case ordered shutdown cannot cover: a kill never gets to ask the worker
	 * to stop, and what would be left is a worker running for a product nobody
	 * has open, holding the installation folder against the next update.
	 */
	@Test
	void leavesWhenTheApplicationDies() throws IOException {
		parent = sleepingJvm();

		watch(parent.pid()).watchParent();

		parent.destroy();

		await().atMost(Duration.ofSeconds(20))
				.untilAsserted(() -> verify(standDown).leave(anyString(), eq(WorkerExitCodes.PARENT_GONE)));
	}

	private ParentProcessWatch watch(Long parentPid) {
		return new ParentProcessWatch(
				new WorkerProperties(null, null, null, null, null, null, null, null, null, parentPid), standDown);
	}

	/**
	 * A JVM that does nothing until it is killed. Started from this installation's
	 * own java binary and classpath, so it needs nothing on the machine.
	 */
	private Process sleepingJvm() throws IOException {
		Path java = Path.of(System.getProperty("java.home"), "bin", "java");

		String binary = java.toFile().exists() ? java.toString() : java + ".exe";

		ProcessBuilder builder = new ProcessBuilder(
				List.of(binary, "-cp", System.getProperty("java.class.path"), SleepingParent.class.getName()));

		Process started = builder.start();

		assertThat(started.isAlive()).isTrue();

		return started;
	}
}