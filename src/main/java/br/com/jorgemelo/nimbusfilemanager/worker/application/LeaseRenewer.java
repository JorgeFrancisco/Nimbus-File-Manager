package br.com.jorgemelo.nimbusfilemanager.worker.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueue;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import lombok.extern.slf4j.Slf4j;

/**
 * Keeps saying "still mine" about every execution this worker holds.
 *
 * <p>
 * Deliberately not the working threads' job. One of them may sit for minutes
 * inside {@code waitFor} on ffmpeg or a slow read, and a lease that depended on
 * it would lapse - handing a job that is very much running to whoever claims
 * next. This renews from a thread that does no work of its own, and does all of
 * them in a single statement, so the cost does not grow with how much is
 * running.
 *
 * <p>
 * If this stops, every lease expires and everything this worker holds is
 * recovered. That is the correct outcome: a worker whose renewer died is a
 * worker nobody should trust to still be there.
 */
@Slf4j
@Component
@Profile(NimbusProfiles.WORKER)
public class LeaseRenewer {

	private final Map<Long, ExecutionOwnership> held = new ConcurrentHashMap<>();

	private final ExecutionQueue executionQueue;
	private final WorkerProperties workerProperties;
	private final String workerId;

	public LeaseRenewer(ExecutionQueue executionQueue, WorkerProperties workerProperties, WorkerIdentity identity) {
		this.executionQueue = executionQueue;
		this.workerProperties = workerProperties;
		this.workerId = identity.workerId();
	}

	/**
	 * Takes the ownership rather than the id, and that is the whole of the fix.
	 * Renewing by id says "this row is still mine" on the strength of a different
	 * connection to the one the locks live in - so a session that died went on
	 * being reported as a healthy owner while it held nothing.
	 */
	public void hold(ExecutionOwnership ownership) {
		held.put(ownership.executionId(), ownership);
	}

	public void release(long executionId) {
		held.remove(executionId);
	}

	/**
	 * One round of renewals, over the executions that still own their locks.
	 *
	 * <p>
	 * An execution whose session is gone is dropped here instead of renewed: its
	 * lease then lapses on its own, and the recovery that exists for abandoned
	 * work takes it back. Renewing it would be this worker insisting on a claim it
	 * can no longer honour - and the whole point of a lease is that it stops
	 * meaning anything when the owner does.
	 *
	 * <p>
	 * A renewal that reaches fewer rows than expected means something was taken
	 * away from us, which recovery will have already acted on, so it is worth a
	 * warning and nothing more.
	 */
	public void renew() {
		List<Long> owned = new ArrayList<>();

		held.forEach((executionId, ownership) -> {
			if (ownership.isStillOwned()) {
				owned.add(executionId);
			} else {
				held.remove(executionId);

				log.warn("Execution {} lost the session holding its locks; its lease will not be renewed",
						executionId);
			}
		});

		if (owned.isEmpty()) {
			return;
		}

		int renewed = executionQueue.renewLeases(workerId, owned, workerProperties.leaseSecondsOrDefault());

		if (renewed < owned.size()) {
			log.warn("Renewed {} of {} leases: something else now owns the difference", renewed, owned.size());
		}
	}
}