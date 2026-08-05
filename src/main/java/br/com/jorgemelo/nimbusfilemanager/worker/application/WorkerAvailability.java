package br.com.jorgemelo.nimbusfilemanager.worker.application;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.worker.application.constants.WorkerHealthConstants;
import br.com.jorgemelo.nimbusfilemanager.worker.application.dto.WorkerAvailabilityResponse;
import br.com.jorgemelo.nimbusfilemanager.worker.domain.model.WorkerInstance;
import br.com.jorgemelo.nimbusfilemanager.worker.domain.repository.WorkerInstanceRepository;

/**
 * Whether there is anything alive to run queued work.
 *
 * <p>
 * The fact, not what to do about it. A screen showing a PENDING execution can
 * ask this to tell "being processed" from "nothing is going to process it", and
 * what it then says to the user - a notice, a refusal, nothing at all - belongs
 * to that screen. Deciding it here would put one answer in front of every
 * caller, and the answers differ: a queued reconcile nobody is waiting on is
 * not the same event as a rename someone is watching.
 *
 * <p>
 * Not {@code WorkerSupervisor.isRunning()}, which this replaces for the
 * question of availability: that one reads a {@link Process} handle in this
 * JVM's memory, so it answers only for a child this very process started, and
 * only while it is the process that started it.
 */
@Component
public class WorkerAvailability {

	private final WorkerInstanceRepository workerInstanceRepository;
	private final Clock clock;

	public WorkerAvailability(WorkerInstanceRepository workerInstanceRepository, Clock clock) {
		this.workerInstanceRepository = workerInstanceRepository;
		this.clock = clock;
	}

	/**
	 * The same fact, shaped for whoever has to show it. {@code lastSeenAt} is read
	 * from every row and not only from the live ones: when nothing is available,
	 * the useful thing to know is when something last was.
	 */
	public WorkerAvailabilityResponse current() {
		List<WorkerInstance> live = liveInstances();

		LocalDateTime lastSeenAt = workerInstanceRepository.findAll().stream().map(WorkerInstance::getLastSeenAt)
				.max(LocalDateTime::compareTo).orElse(null);

		return new WorkerAvailabilityResponse(!live.isEmpty(), live.size(), lastSeenAt);
	}

	/**
	 * Every instance heard from recently. Nimbus starts one worker, so a second
	 * row here is not a scale to celebrate - it is a worker somebody started by
	 * hand, or a supervisor that lost track of its child, and both are worth
	 * being able to see rather than being collapsed into "yes".
	 */
	private List<WorkerInstance> liveInstances() {
		return workerInstanceRepository
				.findByLastSeenAtAfter(LocalDateTime.now(clock).minus(WorkerHealthConstants.FRESH_WITHIN));
	}
}