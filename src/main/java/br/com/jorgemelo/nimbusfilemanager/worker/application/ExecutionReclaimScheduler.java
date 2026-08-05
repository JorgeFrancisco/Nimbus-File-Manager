package br.com.jorgemelo.nimbusfilemanager.worker.application;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.application.LoggingRole;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Asks the recovery the same question the start of the worker asks, over and
 * over, for as long as the worker runs.
 *
 * <p>
 * Recovery used to happen only at startup, and that left a hole with a witness:
 * a worker died holding a RECONCILE whose lease still had minutes left, the
 * replacement started before those minutes were up and correctly found nothing
 * expired, and after that nobody looked again. The row stayed RUNNING, which is
 * what {@code active()} reads as "the system is busy", and the watcher held an
 * inventory it could not launch for six minutes and two restarts. Nothing was
 * corrupted; the system simply had no way back on its own.
 *
 * <p>
 * A thread of its own rather than the renewer's. The renewer is what keeps this
 * worker's own leases alive, and a recovery pass that ran on that thread and
 * took too long would let them lapse - turning this worker into the abandoned
 * one. The two are separated so that the slow one can never be the reason the
 * fast one is late.
 *
 * <p>
 * Started by {@code WorkerConfig} rather than by the constructor, for the same
 * reason the claim loops are: a worker that stood down because the schema is
 * not the one it was built for must not go on writing to that schema.
 */
@Slf4j
@Component
@Profile(NimbusProfiles.WORKER)
public class ExecutionReclaimScheduler {

	private final ExecutionReclaim executionReclaim;
	private final WorkerProperties workerProperties;
	private final AtomicBoolean started = new AtomicBoolean();
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(() -> {
			LoggingRole.markThisThreadAsWorker();

			runnable.run();
		}, "nimbus-execution-reclaim");

		thread.setDaemon(true);

		return thread;
	});

	private volatile boolean shuttingDown;

	public ExecutionReclaimScheduler(ExecutionReclaim executionReclaim, WorkerProperties workerProperties) {
		this.executionReclaim = executionReclaim;
		this.workerProperties = workerProperties;
	}

	/**
	 * Runs as often as a worker promises to speak.
	 *
	 * <p>
	 * The interval is the renewal one because that is the property that already
	 * says how often liveness is refreshed; a separate number would be a second
	 * opinion on the same question, free to drift from it. What it buys is a
	 * bound: an owner that stops renewing is unowned after the lease, and is
	 * recovered within one interval of that - with the shipped values, inside two
	 * and a half minutes of the last heartbeat. The first pass waits an interval
	 * too, because the start of the worker has just run this same rule.
	 */
	public void start() {
		if (!started.compareAndSet(false, true)) {
			return;
		}

		long seconds = workerProperties.renewSecondsOrDefault();

		executor.scheduleWithFixedDelay(this::runOnce, seconds, seconds, TimeUnit.SECONDS);
	}

	/**
	 * A round that fails is a round that failed: the schedule survives it and asks
	 * again, because the condition it is looking for does not go away and the next
	 * answer costs one query. Anything a shutdown knocked over is not news.
	 */
	final void runOnce() {
		try {
			executionReclaim.reclaimAbandoned();
		} catch (Exception exception) {
			if (shuttingDown || Thread.currentThread().isInterrupted()) {
				log.debug("Execution recovery round interrupted during shutdown", exception);
			} else {
				log.error("Execution recovery round failed; the next one will try again", exception);
			}
		}
	}

	/**
	 * Nothing here has to be ordered against the rest of the shutdown, and it is
	 * worth saying why rather than relying on it: a pass only ever writes to rows
	 * whose lease has run out, and a worker on its way out is still inside the
	 * leases it took. Whichever of the two stops first, this one cannot be what
	 * takes work away from the instance it belongs to.
	 */
	@PreDestroy
	void shutdown() {
		shuttingDown = true;

		executor.shutdownNow();
	}
}