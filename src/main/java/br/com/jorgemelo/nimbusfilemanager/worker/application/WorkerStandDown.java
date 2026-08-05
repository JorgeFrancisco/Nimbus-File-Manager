package br.com.jorgemelo.nimbusfilemanager.worker.application;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import lombok.extern.slf4j.Slf4j;

/**
 * How a worker leaves when something outside it decided it should not go on.
 *
 * <p>
 * Two things reach here: the application that started this worker has died, and
 * the database is not the schema this build was made for. Neither is a failure
 * of the work in flight, and both share an order that matters - stop taking new
 * work first, then leave - because the alternative is a process that claims one
 * more execution on its way out.
 *
 * <p>
 * Nothing here waits on the database. When the application dies it can take the
 * PostgreSQL it supervises with it, so a wind-down that queried anything would
 * be a worker that never exits and an installation folder still held against
 * the next update.
 */
@Slf4j
@Component
@Profile(NimbusProfiles.WORKER)
public class WorkerStandDown {

	private final WorkerLoop workerLoop;
	private final WorkerProcessExit workerProcessExit;
	private final AtomicBoolean leaving = new AtomicBoolean();

	public WorkerStandDown(WorkerLoop workerLoop, WorkerProcessExit workerProcessExit) {
		this.workerLoop = workerLoop;
		this.workerProcessExit = workerProcessExit;
	}

	/**
	 * Stops claiming, then ends this process.
	 *
	 * <p>
	 * Idempotent, because the two reasons can arrive together - an application
	 * dying mid-upgrade is also an application whose database is about to be a
	 * different schema - and the second one has nothing left to do.
	 *
	 * @param reason logged: an exit code on its own tells whoever reads the log
	 * nothing
	 * @param exitCode what the supervisor, if there still is one, will read
	 */
	public void leave(String reason, int exitCode) {
		if (!leaving.compareAndSet(false, true)) {
			return;
		}

		log.warn("Worker standing down: {}", reason);

		workerLoop.stopAccepting();

		workerProcessExit.end(exitCode);
	}

	/**
	 * Whether this worker has already been told to go, for anything that must not
	 * start new work during the wind-down.
	 */
	public boolean isLeaving() {
		return leaving.get();
	}
}