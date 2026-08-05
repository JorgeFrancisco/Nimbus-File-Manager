package br.com.jorgemelo.nimbusfilemanager.worker.application;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.worker.application.constants.WorkerHealthConstants;
import br.com.jorgemelo.nimbusfilemanager.worker.domain.model.WorkerInstance;
import br.com.jorgemelo.nimbusfilemanager.worker.domain.repository.WorkerInstanceRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Says, periodically, that this worker exists.
 *
 * <p>
 * Its own thread and its own row, touching nothing else. It is deliberately not
 * the lease renewer, which says "I still own these three executions" and stops
 * having anything to say the moment the worker is idle - an idle worker is
 * exactly the one whose existence a screen most needs to know about.
 *
 * <p>
 * A round that fails is a debug line and nothing more. Losing the database is
 * already fatal to everything the worker does, the next round is ten seconds
 * away, and a worker that killed itself over a heartbeat would be a worker that
 * disappears whenever the thing it reports on hiccups.
 */
@Slf4j
@Component
@Profile(NimbusProfiles.WORKER)
public class WorkerHeartbeat {

	private final WorkerInstanceRepository workerInstanceRepository;
	private final WorkerIdentity workerIdentity;
	private final Clock clock;
	private final ScheduledExecutorService beats = Executors.newSingleThreadScheduledExecutor(WorkerHeartbeat::thread);

	public WorkerHeartbeat(WorkerInstanceRepository workerInstanceRepository, WorkerIdentity workerIdentity,
			Clock clock) {
		this.workerInstanceRepository = workerInstanceRepository;
		this.workerIdentity = workerIdentity;
		this.clock = clock;
	}

	/**
	 * The first beat is written here, before the schedule starts, so that a worker
	 * which has just been told to start claiming is already visible to whoever
	 * asks. Waiting one interval would leave a window in which work is being
	 * claimed by a worker the application would report as absent.
	 *
	 * <p>
	 * A first beat that fails does not stop the worker from starting. Saying "I am
	 * here" is not a precondition for doing the work, and a process that refused to
	 * start over it would be one that disappears whenever the thing it reports on
	 * hiccups.
	 */
	public void start() {
		forgetWorkersNobodyHasSeenInDays();

		beatQuietly();

		long seconds = WorkerHealthConstants.HEARTBEAT_INTERVAL.toSeconds();

		beats.scheduleWithFixedDelay(this::beatQuietly, seconds, seconds, TimeUnit.SECONDS);
	}

	/**
	 * The id carries the pid and the start time, so every restart is a new row and
	 * a supervisor that restarts its child a few times a day would otherwise leave
	 * a table that only grows. Done once, when a worker comes up: whoever is
	 * arriving is the one process certain to be alive.
	 */
	private void forgetWorkersNobodyHasSeenInDays() {
		try {
			workerInstanceRepository
					.deleteByLastSeenAtBefore(LocalDateTime.now(clock).minus(WorkerHealthConstants.FORGET_AFTER));
		} catch (RuntimeException exception) {
			log.debug("Could not clear the rows of workers long gone", exception);
		}
	}

	private void beatQuietly() {
		try {
			beat();
		} catch (RuntimeException exception) {
			log.debug("Heartbeat round failed", exception);
		}
	}

	private void beat() {
		workerInstanceRepository.save(WorkerInstance.builder().workerId(workerIdentity.workerId())
				.lastSeenAt(LocalDateTime.now(clock)).build());
	}

	private static Thread thread(Runnable runnable) {
		Thread thread = new Thread(runnable, "nimbus-worker-heartbeat");

		thread.setDaemon(true);

		return thread;
	}

	/**
	 * The row is left behind on purpose. It is the record of when this worker was
	 * last seen, which is what someone asking "why did nothing run last night?"
	 * needs; a worker that is killed has no chance to delete anything anyway, so
	 * deleting on a clean exit would only make the two shutdowns look different.
	 */
	@PreDestroy
	void stop() {
		beats.shutdownNow();
	}
}