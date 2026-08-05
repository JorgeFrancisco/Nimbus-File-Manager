package br.com.jorgemelo.nimbusfilemanager.worker.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnershipGuard;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionPossession;
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
	private final ExecutionOwnershipGuard executionOwnershipGuard;
	private final WorkerProperties workerProperties;
	private final String workerId;

	public LeaseRenewer(ExecutionQueue executionQueue, ExecutionOwnershipGuard executionOwnershipGuard,
			WorkerProperties workerProperties, WorkerIdentity identity) {
		this.executionQueue = executionQueue;
		this.executionOwnershipGuard = executionOwnershipGuard;
		this.workerProperties = workerProperties;
		this.workerId = identity.workerId();
	}

	/**
	 * Registers which taking of the row this worker is acting under, once the
	 * attempt has been counted and the number exists.
	 *
	 * <p>
	 * It belongs here because this class is already the answer to "what is this
	 * worker holding": the leases it keeps alive and the takings it may write
	 * under are the same set, and splitting them across two owners is how they
	 * would come to disagree. Note that a renewal which fails does not withdraw
	 * the registration - a worker that lost its row has more reason to be stopped
	 * from writing, not less.
	 */
	public void attemptStarted(long executionId, int claimCount) {
		executionOwnershipGuard.takes(new ExecutionPossession(executionId, workerId, claimCount));
	}

	/**
	 * The end of the taking, and deliberately not the end of the lease: renewal
	 * stops when the locks close, but the row goes on being off limits to this
	 * worker until the dispatcher has finished writing about it - the failure path
	 * runs after the locks are gone and writes an outcome of its own.
	 */
	public void attemptEnded(long executionId, int claimCount) {
		executionOwnershipGuard.releases(executionId, claimCount);
	}

	/**
	 * The attempt number this worker is acting under for the row, or empty when it
	 * took the row but never got as far as counting an attempt for it.
	 */
	public OptionalInt claimCountOf(long executionId) {
		return executionOwnershipGuard.claimCountOf(executionId);
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
		List<ExecutionPossession> owned = new ArrayList<>();

		held.forEach((executionId, ownership) -> {
			if (ownership.mayGoOnWorking()) {
				// The taking, not the row: a renewal that named only the execution could
				// extend a later taking of it, and the answer would not say which one the
				// database confirmed.
				executionOwnershipGuard.claimCountOf(executionId)
						.ifPresent(claimCount -> owned.add(new ExecutionPossession(executionId, workerId,
								claimCount)));
			} else {
				held.remove(executionId);

				log.warn("Execution {} lost the session holding its locks; its lease will not be renewed",
						executionId);
			}
		});

		if (owned.isEmpty()) {
			return;
		}

		// Only reached when the statement came back. A round that threw is handled by
		// the caller and records nothing: "I could not ask" and "I was told no" are
		// different answers, and treating the first as the second would stop every
		// execution this worker holds over a moment of unreachable database.
		List<ExecutionPossession> renewed = executionQueue.renewLeases(workerId, owned,
				workerProperties.leaseSecondsOrDefault());

		executionOwnershipGuard.renewalConfirmed(owned, renewed);

		if (renewed.size() < owned.size()) {
			log.warn("Renewed {} of {} leases: something else now owns the difference", renewed.size(), owned.size());
		}
	}
}