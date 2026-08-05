package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.util.Collection;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionPossession;
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueue;

/**
 * Whether a write about an execution is still coming from whoever holds it.
 *
 * <p>
 * Ownership used to be checked once, when an attempt started, and never again.
 * Everything after that - progress, phase, the sentence at the end - was written
 * without asking. That was survivable while recovery only ran at startup,
 * because a worker that had lost its row was almost always a worker that had
 * died. Recovery now runs on a timer, so the ordinary case is different: a
 * worker that paused long enough for its lease to lapse is alive, and it comes
 * back to finish writing about a row that belongs to somebody else.
 *
 * <p>
 * What is registered here is the taking, not the worker: a name that lost a row
 * and claimed it again is a different possession, and the attempt number is
 * what says so. The check is free - the row has just been read by the caller
 * that is about to write to it, and this only compares two of its columns.
 *
 * <p>
 * An execution nobody in this process is holding is not guarded, and that is
 * the point rather than an omission. Cancelling from the screen, the recovery
 * pass itself, an administrative close: those are commands about an execution
 * rather than the work reporting on itself, and they must go through whatever
 * the row currently says. The rule being enforced is narrower than "only the
 * owner writes" - it is "a worker that lost its turn stops writing".
 */
@Component
public class ExecutionOwnershipGuard {

	/**
	 * Cancelling from the screen, the recovery pass, an administrative close: none
	 * of them is a taking reporting on its own work, and none of them has one to
	 * name. They answer to the row as it stands.
	 */
	private static final boolean EXTERNAL_COMMANDS_ARE_NOT_FENCED = true;

	private final Map<Long, ExecutionPossession> possessions = new ConcurrentHashMap<>();

	/**
	 * Takings the database has positively said are no longer ours. Only a renewal
	 * round that ran to completion writes here: a round that failed knows nothing,
	 * and treating "I could not ask" as "I was told no" would stop every execution
	 * this worker holds over a moment of unreachable database.
	 */
	private final Set<ExecutionPossession> confirmedLost = ConcurrentHashMap.newKeySet();

	private final ExecutionQueue executionQueue;

	public ExecutionOwnershipGuard(ExecutionQueue executionQueue) {
		this.executionQueue = executionQueue;
	}

	/**
	 * Holds the taking in force for the rest of the caller's transaction, so that
	 * the domain write about to happen cannot be overtaken between here and the
	 * commit.
	 *
	 * <p>
	 * The authority, and the reason the cooperative checks elsewhere are allowed
	 * to be approximate. An execution this process is not holding is not pinned
	 * and not refused: those are commands about an execution rather than the work
	 * reporting on itself, and they answer to the row as it stands.
	 */
	public boolean pin(long executionId, int claimCount) {
		ExecutionPossession heldHere = possessions.get(executionId);

		if (heldHere == null) {
			return EXTERNAL_COMMANDS_ARE_NOT_FENCED;
		}

		// The worker name is the one thing taken from the local registration: it is
		// fixed for the process. The attempt number is the caller's own, never the
		// registered one - substituting it would let a taking that is over borrow the
		// authority of the one that replaced it, and would decide here what only the
		// row can decide.
		return executionQueue.pin(executionId, heldHere.workerId(), claimCount);
	}

	/**
	 * The taking this process registered for the execution, when the caller is
	 * asking about that one and not a different taking of the same row.
	 *
	 * <p>
	 * Compared by attempt number rather than looked up by id alone, because two
	 * takings of one execution can be live in a single process: recovery puts the
	 * row back, another loop of this same worker claims it, and for a while the
	 * old one is still running. Answering by id would hand the finished taking the
	 * live one's identity - which is the one answer that must never be given.
	 */
	private ExecutionPossession registered(long executionId, int claimCount) {
		ExecutionPossession possession = possessions.get(executionId);

		return possession != null && possession.claimCount() == claimCount ? possession : null;
	}

	/**
	 * What a renewal round that completed learned: the ids it asked about and the
	 * ones the database renewed. The difference is a positive answer - the row is
	 * no longer this worker's - and only that difference is recorded.
	 */
	public void renewalConfirmed(Collection<ExecutionPossession> asked, Collection<ExecutionPossession> renewed) {
		asked.stream().filter(taking -> !renewed.contains(taking)).forEach(confirmedLost::add);
	}

	/** Registered when an attempt starts for real, and only then. */
	public void takes(ExecutionPossession possession) {
		possessions.put(possession.executionId(), possession);
	}

	/**
	 * Forgets a taking, and only if it is still the registered one: a taking that
	 * ends after a later one has begun must not take the live registration with
	 * it.
	 */
	public void releases(long executionId, int claimCount) {
		ExecutionPossession possession = registered(executionId, claimCount);

		if (possession == null) {
			return;
		}

		possessions.remove(executionId, possession);

		confirmedLost.remove(possession);
	}

	/**
	 * The attempt number this process is acting under for the row, when it is
	 * acting under one at all - a hand-back before the attempt was counted has no
	 * possession to name.
	 */
	public OptionalInt claimCountOf(long executionId) {
		ExecutionPossession possession = possessions.get(executionId);

		return possession == null ? OptionalInt.empty() : OptionalInt.of(possession.claimCount());
	}

	/**
	 * Whether the taking is still the one this process holds for the execution and
	 * has not been told otherwise. Answers from memory, and answers yes for a
	 * taking nobody registered - the cooperative half is allowed to be
	 * approximate, because the write is what {@link #pin} decides.
	 *
	 * <p>
	 * About the taking and nothing else. Whether the paths it works over are still
	 * locked is a different question, asked of a different thing, and the two are
	 * kept apart on purpose - see {@link ExecutionOwnership}.
	 */
	public boolean isTheCurrentTaking(long executionId, int claimCount) {
		ExecutionPossession heldHere = possessions.get(executionId);

		if (heldHere == null) {
			return EXTERNAL_COMMANDS_ARE_NOT_FENCED;
		}

		if (heldHere.claimCount() != claimCount) {
			// A later taking of the same row is registered here, so the caller's is over.
			return false;
		}

		return !confirmedLost.contains(heldHere);
	}
}