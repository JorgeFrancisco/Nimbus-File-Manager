package br.com.jorgemelo.nimbusfilemanager.worker.infrastructure.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;

import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueueSignals;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.worker.application.ExecutionReclaim;
import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerHeartbeat;
import br.com.jorgemelo.nimbusfilemanager.worker.application.SchemaCompatibility;
import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerLoop;
import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerStandDown;
import br.com.jorgemelo.nimbusfilemanager.worker.application.constants.WorkerExitCodes;
import lombok.extern.slf4j.Slf4j;

/**
 * Starts the claim loops, once the context is up.
 *
 * <p>
 * On {@code ApplicationReadyEvent} rather than at bean creation: a loop that
 * started while the context was still wiring could claim an execution and hand
 * it to a handler that does not exist yet. By the time this fires, everything
 * it might need has been built.
 *
 * <p>
 * Guarded by the {@code worker} profile, which is the whole of what makes a
 * process a worker. Without it the same jar is the application - it serves the
 * screens, supervises the database and produces the work - and claims nothing.
 *
 * <p>
 * The schema is checked before the loops exist, and that ordering is the
 * guarantee rather than a precaution: a worker refused by the check never
 * starts a loop, so there is no path by which it claims anything. A flag the
 * loops consulted would leave one - the flag would have to be read, and reading
 * it happens after the loop is running.
 */
@Slf4j
@Configuration
@Profile(NimbusProfiles.WORKER)
public class WorkerConfig {

	private final WorkerLoop workerLoop;
	private final SchemaCompatibility schemaCompatibility;
	private final WorkerStandDown workerStandDown;
	private final ExecutionReclaim executionReclaim;
	private final ExecutionQueueSignals executionQueueSignals;
	private final WorkerHeartbeat workerHeartbeat;

	public WorkerConfig(WorkerLoop workerLoop, SchemaCompatibility schemaCompatibility,
			WorkerStandDown workerStandDown, ExecutionReclaim executionReclaim,
			ExecutionQueueSignals executionQueueSignals, WorkerHeartbeat workerHeartbeat) {
		this.workerLoop = workerLoop;
		this.schemaCompatibility = schemaCompatibility;
		this.workerStandDown = workerStandDown;
		this.executionReclaim = executionReclaim;
		this.executionQueueSignals = executionQueueSignals;
		this.workerHeartbeat = workerHeartbeat;
	}

	@EventListener(ApplicationReadyEvent.class)
	void startClaiming() {
		if (!schemaCompatibility.isCompatible()) {
			workerStandDown.leave("the database schema is not the one this build was made for",
					WorkerExitCodes.SCHEMA_INCOMPATIBLE);

			return;
		}

		// Before the first claim, and only after the schema is known to fit: work a
		// dead worker left behind is taken back here, where nothing is running yet.
		executionReclaim.reclaimAbandoned();

		// Said before the loops exist, so that a worker which has been told to start
		// is never reported as absent while it is already claiming. Both of these are
		// started only past the schema check: a worker that stood down must not
		// announce itself as available to run work it has just refused to run.
		workerHeartbeat.start();
		executionQueueSignals.start();

		log.info("Worker ready: claiming executions from the queue");

		workerLoop.start();
	}
}