package br.com.jorgemelo.nimbusfilemanager.worker.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerProperties;
import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerSupervisor;

/**
 * The process boundary, against a real second JVM.
 *
 * <p>
 * Everything else about the worker is asserted with a mocked {@link Process},
 * which is right for the supervisor's decisions and useless for the thing that
 * actually goes wrong here: whether the handle the application keeps belongs to
 * the process it started. It would not, if the launch ever went through a shell
 * - {@code cmd /c start} hands back the shell, which exits at once, and then the
 * pid is wrong, {@code onExit} fires immediately and {@code destroy} reaches
 * nobody. This application has been bitten by exactly that in its update script,
 * so the guarantee is worth a real process.
 *
 * <p>
 * The child is a JVM that sleeps rather than the application itself: starting
 * Nimbus would need a database and would be testing Nimbus. What is under test
 * is the boundary.
 */
class WorkerProcessLifecycleIntegrationTest {

	private final List<Process> started = new ArrayList<>();

	@AfterEach
	void killWhateverSurvived() {
		started.forEach(Process::destroyForcibly);
	}

	@Test
	void keepsAHandleOnTheProcessItStarted() throws Exception {
		Process worker = start();

		assertThat(worker.isAlive()).isTrue();
		assertThat(worker.pid()).isNotEqualTo(ProcessHandle.current().pid());
		assertThat(ProcessHandle.of(worker.pid())).isPresent();
	}

	/**
	 * The handle must be the worker's own, not a launcher that already exited -
	 * which is what a shell in between would give.
	 */
	@Test
	void staysAliveInsteadOfExitingImmediately() throws Exception {
		Process worker = start();

		assertThat(worker.waitFor(2, TimeUnit.SECONDS)).isFalse();
		assertThat(worker.isAlive()).isTrue();
	}

	/**
	 * The signal the supervisor restarts on. Asserted through waitFor rather than
	 * isAlive: onExit completes as the process ends, and on Windows the liveness
	 * flag can still read true for an instant afterwards - a race that would make
	 * this flaky for no reason.
	 */
	@Test
	void noticesWhenTheProcessDies() throws Exception {
		Process worker = start();

		CompletableFuture<Process> exited = worker.onExit();

		worker.destroy();

		assertThat(exited.get(20, TimeUnit.SECONDS)).isSameAs(worker);
		assertThat(worker.waitFor(20, TimeUnit.SECONDS)).isTrue();
	}

	/**
	 * An update or an uninstall fails outright while a process still holds the
	 * installed files, so the supervisor's stop has to actually end the JVM.
	 */
	@Test
	void supervisorStopEndsTheRealProcess() throws Exception {
		SleepingProcessLauncher launcher = new SleepingProcessLauncher();

		WorkerSupervisor supervisor = new WorkerSupervisor(launcher,
				new WorkerProperties(null, null, null, null, null, null, 10, null, null, null), Clock.systemUTC());

		supervisor.start();

		started.addAll(launcher.everyStarted());

		assertThat(supervisor.isRunning()).isTrue();

		long pid = launcher.lastStarted().pid();

		supervisor.stop();

		assertThat(launcher.lastStarted().waitFor(20, TimeUnit.SECONDS)).isTrue();
		assertThat(ProcessHandle.of(pid).filter(ProcessHandle::isAlive)).isEmpty();
	}

	/**
	 * A worker asked to stop must not be replaced. The restart callback and the
	 * shutdown race by nature, and getting this wrong leaves a JVM behind a
	 * process that is going away - the orphan the packaging has to never see.
	 */
	@Test
	void leavesNoOrphanBehindWhenTheSupervisorStops() {
		SleepingProcessLauncher launcher = new SleepingProcessLauncher();

		WorkerSupervisor supervisor = new WorkerSupervisor(launcher,
				new WorkerProperties(null, null, null, null, null, null, 10, null, null, null), Clock.systemUTC());

		supervisor.start();

		started.addAll(launcher.everyStarted());

		supervisor.stop();

		// Give the restart callback every chance to fire before asserting it did not:
		// asserting immediately would pass even if the supervisor were about to start
		// a replacement.
		await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(20))
				.until(() -> launcher.everyStarted().stream().noneMatch(Process::isAlive));

		assertThat(launcher.everyStarted()).hasSize(1);
	}

	private Process start() throws IOException {
		Process worker = new SleepingProcessLauncher().launch();

		started.add(worker);

		return worker;
	}
}