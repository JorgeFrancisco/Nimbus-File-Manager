package br.com.jorgemelo.nimbusfilemanager.worker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.worker.application.constants.WorkerExitCodes;

/**
 * Supervising the second JVM.
 *
 * <p>
 * The {@link Process} is mocked rather than started, because what is worth
 * asserting is the supervisor's own decisions - restart a worker that died,
 * leave one alone that was asked to stop, kill one that will not - and starting
 * a JVM per case would test the operating system instead. The real boundary
 * belongs to the out-of-process tests the plan calls for.
 */
class WorkerSupervisorTest {

	private final WorkerProcessLauncher launcher = mock(WorkerProcessLauncher.class);

	private final WorkerSupervisor supervisor = new WorkerSupervisor(launcher,
			new WorkerProperties(null, null, null, null, null, null, null, null, null, null), Clock.systemUTC());

	@Test
	void startsTheWorkerAndKeepsItsHandle() throws IOException {
		Process worker = alive();

		when(launcher.launch()).thenReturn(worker);

		supervisor.start();

		assertThat(supervisor.isRunning()).isTrue();

		verify(launcher).launch();
	}

	@Test
	void reportsNoWorkerWhenItCouldNotBeStarted() throws IOException {
		when(launcher.launch()).thenThrow(new IOException("no java"));

		supervisor.start();

		assertThat(supervisor.isRunning()).isFalse();
	}

	/**
	 * A worker that dies on its own is started again: the work it was claiming is
	 * still in the queue, and an application with no worker quietly stops
	 * processing anything.
	 */
	@Test
	void startsAnotherWorkerWhenOneDiesUnasked() throws IOException {
		WorkerSupervisor working = supervisorOn(new SteppingClock(Duration.ofMinutes(2)));

		Process died = mock(Process.class);

		Process replacement = alive();

		when(died.onExit()).thenReturn(CompletableFuture.completedFuture(died));
		when(died.exitValue()).thenReturn(1);
		when(launcher.launch()).thenReturn(died, replacement);

		working.start();

		verify(launcher, times(2)).launch();
	}

	/**
	 * The other half of the same decision, and the one that was missing: a worker
	 * that never got going - an incompatible schema, a database that will not
	 * answer - dies in a second, and replacing it at once makes the application
	 * start JVMs as fast as they can fail. The wait is what turns that into an
	 * attempt.
	 */
	@Test
	void waitsBeforeReplacingAWorkerThatFailedToStart() throws IOException {
		WorkerSupervisor failing = supervisorOn(new SteppingClock(Duration.ZERO));

		Process died = mock(Process.class);

		Process replacement = alive();

		when(died.onExit()).thenReturn(CompletableFuture.completedFuture(died));
		when(died.exitValue()).thenReturn(WorkerExitCodes.SCHEMA_INCOMPATIBLE);
		when(launcher.launch()).thenReturn(died, replacement);

		failing.start();

		verify(launcher, times(1)).launch();

		await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> verify(launcher, times(2)).launch());
	}

	private WorkerSupervisor supervisorOn(Clock clock) {
		return new WorkerSupervisor(launcher,
				new WorkerProperties(null, null, null, null, null, null, null, null, null, null), clock);
	}

	@Test
	void asksTheWorkerToStopAndWaitsForIt() throws Exception {
		Process worker = alive();

		when(worker.waitFor(anyLong(), any())).thenReturn(true);
		when(launcher.launch()).thenReturn(worker);

		supervisor.start();
		supervisor.stop();

		verify(worker).destroy();
		verify(worker, never()).destroyForcibly();
	}

	/**
	 * An update or an uninstall fails outright while a process still holds the
	 * installed files, so a worker that will not stop is killed rather than
	 * waited for indefinitely.
	 */
	@Test
	void killsAWorkerThatWillNotStop() throws Exception {
		Process worker = alive();

		when(worker.waitFor(anyLong(), any())).thenReturn(false);
		when(launcher.launch()).thenReturn(worker);

		supervisor.start();
		supervisor.stop();

		verify(worker).destroyForcibly();
	}

	@Test
	void doesNotRestartAWorkerItAskedToStop() throws Exception {
		Process worker = mock(Process.class);

		CompletableFuture<Process> exit = new CompletableFuture<>();

		when(worker.isAlive()).thenReturn(true);
		when(worker.onExit()).thenReturn(exit);
		when(worker.waitFor(anyLong(), any())).thenReturn(true);
		when(launcher.launch()).thenReturn(worker);

		supervisor.start();
		supervisor.stop();

		exit.complete(worker);

		verify(launcher, times(1)).launch();
	}

	@Test
	void hasNothingToStopWhenNoWorkerWasStarted() {
		supervisor.stop();

		assertThat(supervisor.isRunning()).isFalse();
	}

	/**
	 * Once stopped, it stays stopped. A start arriving after shutdown - a late
	 * event, a retry - must not leave a worker behind a process that is going
	 * away.
	 */
	@Test
	void refusesToStartAfterItHasBeenStopped() throws IOException {
		supervisor.stop();
		supervisor.start();

		verify(launcher, never()).launch();
	}

	@Test
	void reportsNoWorkerWhenTheOneItStartedHasDied() throws IOException {
		Process worker = mock(Process.class);

		when(worker.isAlive()).thenReturn(false);
		when(worker.onExit()).thenReturn(new CompletableFuture<>());
		when(launcher.launch()).thenReturn(worker);

		supervisor.start();

		assertThat(supervisor.isRunning()).isFalse();
	}

	/**
	 * Being interrupted while waiting is not a reason to leave the worker
	 * running: the flag is restored and the process is killed, because whatever
	 * asked this thread to stop is on its way out too.
	 */
	@Test
	void killsTheWorkerWhenTheWaitIsInterrupted() throws Exception {
		Process worker = alive();

		when(worker.waitFor(anyLong(), any())).thenThrow(new InterruptedException());
		when(launcher.launch()).thenReturn(worker);

		supervisor.start();

		try {
			supervisor.stop();

			assertThat(Thread.currentThread().isInterrupted()).isTrue();

			verify(worker).destroyForcibly();
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	void hasNothingToStopWhenTheWorkerAlreadyExited() throws Exception {
		Process worker = mock(Process.class);

		when(worker.isAlive()).thenReturn(false);
		when(worker.onExit()).thenReturn(new CompletableFuture<>());
		when(launcher.launch()).thenReturn(worker);

		supervisor.start();
		supervisor.stop();

		verify(worker, never()).destroy();
	}

	private Process alive() {
		Process worker = mock(Process.class);

		when(worker.isAlive()).thenReturn(true);
		when(worker.onExit()).thenReturn(new CompletableFuture<>());

		return worker;
	}
}