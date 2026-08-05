package br.com.jorgemelo.nimbusfilemanager.worker.application;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Starts the worker process and keeps it alive.
 *
 * <p>
 * The pattern is not new here: this application already supervises a child
 * process - the embedded PostgreSQL - and the same rules apply. The
 * {@link Process} is retained, so the pid is the worker's own and
 * {@code onExit} means the worker died rather than something in between. That
 * is why nothing launches it through a shell: {@code cmd /c start} would make
 * the retained handle belong to the shell, which exits immediately, leaving a
 * pid that is not the worker's, an {@code onExit} that fires at the wrong
 * moment and a {@code destroy} that reaches nobody. This application has been
 * bitten by exactly that in its update script.
 *
 * <p>
 * Existence is the whole of the role check. Where the worker runs inside this
 * JVM there is nothing to start, so this bean is simply not created - which is
 * why there is no {@code if (combined)} anywhere below.
 */
@Slf4j
@Component
@Profile(NimbusProfiles.APP + " & !" + NimbusProfiles.WORKER)
public class WorkerSupervisor {

	private final WorkerProcessLauncher workerProcessLauncher;
	private final WorkerProperties workerProperties;

	/**
	 * The worker process, and whether one is still wanted.
	 *
	 * <p>
	 * Both are read and written from different threads - {@code start} from the
	 * ready event, {@code stop} from shutdown, and the restart callback from
	 * whichever thread completes {@code onExit}. Atomic references rather than
	 * volatile fields because the process is replaced on restart, and a volatile
	 * reference publishes the new value without making the swap itself safe.
	 */
	private final AtomicReference<Process> worker = new AtomicReference<>();

	private final AtomicBoolean wanted = new AtomicBoolean(true);

	/**
	 * How many workers in a row have failed to start, and when the current one
	 * did. Together they are the difference between replacing a worker that was
	 * working and spawning JVMs at the speed they can fail.
	 */
	private final AtomicInteger consecutiveFailures = new AtomicInteger();

	private final AtomicReference<Instant> startedAt = new AtomicReference<>();

	private final ScheduledExecutorService restarts = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "nimbus-worker-restart");

		thread.setDaemon(true);

		return thread;
	});

	private final Clock clock;

	public WorkerSupervisor(WorkerProcessLauncher workerProcessLauncher, WorkerProperties workerProperties,
			Clock clock) {
		this.workerProcessLauncher = workerProcessLauncher;
		this.workerProperties = workerProperties;
		this.clock = clock;
	}

	/**
	 * Starts the worker, and arranges for it to be started again if it dies while
	 * it is still wanted.
	 */
	public void start() {
		if (!wanted.get()) {
			return;
		}

		try {
			Process started = workerProcessLauncher.launch();

			worker.set(started);
			startedAt.set(clock.instant());

			log.info("Worker started with pid {}", started.pid());

			started.onExit().thenAccept(this::restartIfStillWanted);
		} catch (IOException exception) {
			log.error("Could not start the worker process", exception);
		}
	}

	public boolean isRunning() {
		Process running = worker.get();

		return running != null && running.isAlive();
	}

	/**
	 * Ends the worker before this process goes away.
	 *
	 * <p>
	 * Ordered, not abrupt: the worker is asked to stop, given time to finish the
	 * batch it is on, and only killed if it will not. An update or an uninstall
	 * fails outright when a process is still holding the installed files, which
	 * makes this the difference between an upgrade that works and one that has to
	 * be repaired by hand.
	 */
	@PreDestroy
	public void stop() {
		wanted.set(false);

		restarts.shutdownNow();

		Process running = worker.get();

		if (running == null || !running.isAlive()) {
			return;
		}

		running.destroy();

		try {
			if (!running.waitFor(workerProperties.shutdownSecondsOrDefault(), TimeUnit.SECONDS)) {
				log.warn("Worker did not stop within the grace period and is being killed");

				running.destroyForcibly();
			}
		} catch (InterruptedException _) {
			Thread.currentThread().interrupt();

			running.destroyForcibly();
		}
	}

	/**
	 * What to do about a worker that has exited: replace it at once if it was
	 * working, wait longer each time if it is failing to start, and stop
	 * altogether once trying has stopped being useful.
	 */
	private void restartIfStillWanted(Process exited) {
		if (!wanted.get()) {
			return;
		}

		Duration lifetime = Duration.between(startedAt.get(), clock.instant());

		int failures = consecutiveFailures
				.updateAndGet(previous -> WorkerRestartPolicy.consecutiveFailuresAfter(previous, lifetime));

		if (WorkerRestartPolicy.givesUpAfter(failures)) {
			// Louder than another attempt, and quieter than a machine spawning
			// processes: the application goes on serving screens with no worker, which
			// is a visible absence rather than an invisible loop.
			log.error("Worker exited with code {} after {} s, failing to start {} times in a row. Not starting "
					+ "another one; background work will not run until this is restarted.", exited.exitValue(),
					lifetime.toSeconds(), failures);

			return;
		}

		Duration delay = WorkerRestartPolicy.delayAfter(failures);

		log.warn("Worker exited with code {} after {} s; starting another in {} s", exited.exitValue(),
				lifetime.toSeconds(), delay.toSeconds());

		if (delay.isZero()) {
			// A worker that was working is replaced here and now, on the thread that
			// noticed - which is what this did before there was any waiting at all.
			start();

			return;
		}

		scheduleStart(delay);
	}

	private void scheduleStart(Duration delay) {
		try {
			restarts.schedule(this::start, delay.toMillis(), TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException _) {
			// The scheduler is closed, which only happens once stop() has run - and a
			// worker nobody wants any more needs no replacement.
			log.debug("A worker restart was not scheduled because this application is shutting down");
		}
	}
}