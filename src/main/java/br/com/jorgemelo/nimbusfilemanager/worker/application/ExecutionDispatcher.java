package br.com.jorgemelo.nimbusfilemanager.worker.application;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OwnershipLostException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueue;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import lombok.extern.slf4j.Slf4j;

/**
 * Takes one execution off the queue and sees it through.
 *
 * <p>
 * The order of the steps is the architecture, not a detail. Reserving is a
 * short transaction that ends before any work starts, so no row lock is held
 * while files move. The path locks come next, in another connection entirely,
 * because whether a tree is free is not something the queue's SELECT can see.
 * The attempt is counted only once both are held - a path that happened to be
 * busy is not a failed attempt, and charging one would spend the poison-job
 * budget on contention. And nothing of the domain runs until that count is
 * persisted, so no execution can run without having been counted.
 */
@Slf4j
@Component
@Profile(NimbusProfiles.WORKER)
public class ExecutionDispatcher {

	private final ExecutionQueue executionQueue;
	private final ExecutionRepository executionRepository;
	private final ExecutionProgressService executionProgressService;
	private final OperationLockService operationLockService;
	private final LeaseRenewer leaseRenewer;
	private final WorkerProperties workerProperties;
	private final CategoryConcurrency categoryConcurrency;
	private final Map<ExecutionType, ExecutionJobHandler> handlers;
	private final String workerId;

	public ExecutionDispatcher(ExecutionQueue executionQueue, ExecutionRepository executionRepository,
			ExecutionProgressService executionProgressService, OperationLockService operationLockService,
			LeaseRenewer leaseRenewer, WorkerProperties workerProperties, CategoryConcurrency categoryConcurrency,
			List<ExecutionJobHandler> handlers, WorkerIdentity identity) {
		this.executionQueue = executionQueue;
		this.executionRepository = executionRepository;
		this.executionProgressService = executionProgressService;
		this.operationLockService = operationLockService;
		this.leaseRenewer = leaseRenewer;
		this.workerProperties = workerProperties;
		this.categoryConcurrency = categoryConcurrency;
		this.handlers = handlers.stream()
				.collect(Collectors.toMap(ExecutionJobHandler::type, Function.identity()));
		this.workerId = identity.workerId();
	}

	/**
	 * Attempts to start one execution.
	 *
	 * @return true when one was taken and run, so the caller knows whether to ask
	 * again straight away or wait
	 */
	public boolean dispatchOne() {
		List<String> types = categoryConcurrency.typesWithCapacity();

		if (types.isEmpty()) {
			// Everything this worker can run is already running as many as it may.
			return false;
		}

		Optional<ClaimedExecution> claimed = executionQueue.reserve(workerId, types,
				workerProperties.maxClaimsOrDefault(), workerProperties.leaseSecondsOrDefault());

		if (claimed.isEmpty()) {
			return false;
		}

		return runWithinItsCategory(claimed.get());
	}

	/**
	 * Between asking which types had room and reserving one, another loop may have
	 * taken the last slot. The execution goes straight back rather than waiting:
	 * holding a claimed row with a lease ticking, for work this worker is not
	 * allowed to start, is exactly what the limit exists to avoid.
	 */
	private boolean runWithinItsCategory(ClaimedExecution claimed) {
		if (!categoryConcurrency.tryEnter(typeOf(claimed))) {
			handBack(claimed, beforeAnyTaking(claimed));

			return false;
		}

		OptionalInt attempt = OptionalInt.empty();

		try {
			attempt = runClaimed(claimed);
		} finally {
			// Last, because everything that writes an outcome for this row - including
			// the failure path, which runs after the locks are already closed - has to
			// be inside the taking it is writing about.
			attempt.ifPresent(claimCount -> leaseRenewer.attemptEnded(claimed.id(), claimCount));

			categoryConcurrency.leave(typeOf(claimed));
		}

		return true;
	}

	private OptionalInt runClaimed(ClaimedExecution claimed) {
		try {
			if (!canTakeWhatItNeeds(claimed)) {
				return OptionalInt.empty();
			}

			try (ExecutionOwnership ownership = ownershipFor(claimed)) {
				// The renewer holds the ownership rather than the id, so a session that dies
				// stops the lease with it. Held only from here on: before the locks exist
				// there is nothing to say is still ours.
				leaseRenewer.hold(ownership);

				try {
					return runOwned(claimed, ownership);
				} finally {
					leaseRenewer.release(claimed.id());
				}
			}
		} catch (OperationLockException exception) {
			// The tree is busy. Not a failed attempt and not an error - the execution
			// simply goes back to the queue to be picked up once whoever holds it is
			// done, with its attempt budget untouched.
			log.info("Execution {} handed back: {}", claimed.id(), exception.getMessage());

			handBack(claimed, beforeAnyTaking(claimed));
		} catch (RuntimeException exception) {
			chargeAndFail(claimed, exception);
		}

		return OptionalInt.empty();
	}

	/**
	 * What this worker holds over a row it has reserved but has not begun: the
	 * claim, and no taking of it.
	 *
	 * <p>
	 * Every write about an execution names the taking it is made under, and here
	 * there is none to name - the attempt was never counted, so there is no number
	 * a later taking could differ from, and nothing for the row to be fenced
	 * against. What answers for these writes is the claim itself, which is the same
	 * authority the recovery pass writes under. It costs nothing: no lock is taken
	 * and no session is opened.
	 *
	 * <p>
	 * Every call site sits before {@code claim_count} moves, and that is a
	 * property of the shape rather than of what usually happens:
	 * {@link #runUnderTheTaking} lets nothing escape once the number exists, and
	 * the only two statements that run after it - handing the renewal back and
	 * closing the locks - cannot throw, each swallowing its own database failure.
	 * The tests hold the line from the other side.
	 */
	private ExecutionOwnership beforeAnyTaking(ClaimedExecution claimed) {
		return operationLockService.acquireNothingFor(claimed.id());
	}

	/**
	 * Something went wrong between the claim and the attempt being counted, and it
	 * was not the paths being busy.
	 *
	 * <p>
	 * Nothing may escape this window, and the reason is a bug this code already
	 * had: a fingerprint backlog reached the dispatcher, threw before
	 * {@code claim_count} could move, and left the row RUNNING with a counter of
	 * zero. The lease lapsed, recovery put it back, and it threw again - forever,
	 * because the poison-job brake reads exactly the counter that never moved. The
	 * particular cause was fixed; the shape of it was not, and any failure here -
	 * a lock session that cannot be opened, a path column no longer readable - has
	 * the same shape.
	 *
	 * <p>
	 * So the attempt is charged first and the failure is then treated exactly as
	 * one during the work: retried while the budget allows, ended for good when it
	 * does not. A passing failure costs one attempt out of several, which is the
	 * price of the guarantee that no execution can be retried without limit.
	 */
	private void chargeAndFail(ClaimedExecution claimed, RuntimeException exception) {
		executionQueue.countAttempt(claimed.id(), workerId, workerProperties.maxClaimsOrDefault());

		findExecution(claimed.id()).ifPresentOrElse(
				execution -> afterFailure(execution, claimed, beforeAnyTaking(claimed), exception),
				() -> log.error("Execution {} failed before it could start, and is no longer there to be told so",
						claimed.id(), exception));
	}

	/**
	 * The locks this execution needs, or none when its work is a query rather than
	 * a folder.
	 *
	 * <p>
	 * Which of the two it is comes from the handler, never from whether the row
	 * happens to have a path filled in: "no path, so no lock" would let a mutating
	 * execution slip past the exclusion by forgetting a field.
	 */
	private ExecutionOwnership ownershipFor(ClaimedExecution claimed) {
		if (!requiresPathLock(claimed)) {
			return operationLockService.acquireNothingFor(claimed.id());
		}

		return operationLockService.acquireFor(claimed.id(), typeOf(claimed), pathsOf(claimed));
	}

	/**
	 * An execution whose type is defined by a place on disk, but whose row names
	 * none, cannot run: there is nothing to take the locks over, and running
	 * without them is the one thing the exclusion exists to prevent.
	 *
	 * <p>
	 * It fails here rather than throwing out of the dispatcher. Left to escape, it
	 * killed the attempt before {@code claim_count} was incremented - so the
	 * poison-job brake never engaged, and the execution was reclaimed and re-thrown
	 * for as long as anybody kept a worker running.
	 */
	private boolean canTakeWhatItNeeds(ClaimedExecution claimed) {
		if (!requiresPathLock(claimed) || pathsOf(claimed).length > 0) {
			return true;
		}

		log.error("Execution {} of type {} needs a path to lock and names none; it cannot run", claimed.id(),
				claimed.executionType());

		executionProgressService.fail(beforeAnyTaking(claimed), ExecutionMessages.executionInterrupted());

		return false;
	}

	private boolean requiresPathLock(ClaimedExecution claimed) {
		ExecutionJobHandler handler = handlers.get(typeOf(claimed));

		return handler == null || handler.requiresPathLock();
	}

	/**
	 * With the locks in hand: count the attempt, confirm they are still held, and
	 * only then run.
	 *
	 * <p>
	 * The confirmation is not a formality. Between taking the locks and getting
	 * here there was a round trip to the database, and a session that died in it
	 * would leave this worker holding nothing while believing otherwise - the one
	 * state in which two processes write the same file.
	 */
	private OptionalInt runOwned(ClaimedExecution claimed, ExecutionOwnership ownership) {
		OptionalInt attempt = executionQueue.countAttempt(claimed.id(), workerId,
				workerProperties.maxClaimsOrDefault());

		if (attempt.isEmpty()) {
			refuseToStart(claimed, ownership);

			return OptionalInt.empty();
		}

		runUnderTheTaking(claimed, ownership, attempt.getAsInt());

		return attempt;
	}

	/**
	 * Everything from the moment the attempt is counted, and the line that makes
	 * {@link #beforeAnyTaking} unreachable past it.
	 *
	 * <p>
	 * From here to the end of the run, everything this worker writes about the row
	 * is checked against this taking of it. The number is what separates this one
	 * from a later taking by the same name, which is what recovery can produce out
	 * of a worker that only looked dead. Both halves of the same fact happen first:
	 * the object the handler carries becomes this taking, and the worker's set of
	 * what it holds learns about it.
	 *
	 * <p>
	 * Nothing may leave this method, and that is the whole reason it exists. The
	 * handlers outside it write under the claim alone - the right authority while
	 * no taking exists, and the wrong one from here on, because a write with no
	 * number is a write a later taking of the same row cannot be told apart from.
	 * A failure that escaped to them would be exactly the one that matters: a
	 * database that blinked while the taking was being born, and answered again by
	 * the time the outcome was written.
	 */
	private void runUnderTheTaking(ClaimedExecution claimed, ExecutionOwnership ownership, int claimCount) {
		try {
			ownership.attemptStarted(claimCount);

			leaseRenewer.attemptStarted(claimed.id(), claimCount);

			execute(claimed, ownership);
		} catch (RuntimeException exception) {
			failUnderTheTaking(claimed, ownership, exception);
		}
	}

	/**
	 * The outcome for a failure the handler never got to write one for - the row
	 * could not be read, or writing the outcome threw - made under the same taking
	 * the work ran as.
	 *
	 * <p>
	 * The end of the line: it does not rethrow. A row nobody could write about
	 * here is one whose lease lapses and which recovery closes, and recovery is
	 * the frontier built for exactly that - where escalating would instead hand
	 * the row to a handler that no longer has the right authority for it.
	 */
	private void failUnderTheTaking(ClaimedExecution claimed, ExecutionOwnership ownership,
			RuntimeException exception) {
		try {
			findExecution(claimed.id()).ifPresentOrElse(
					execution -> afterFailure(execution, claimed, ownership, exception),
					() -> log.error("Execution {} failed and is no longer there to be told so", claimed.id(),
							exception));
		} catch (RuntimeException whileWriting) {
			log.error("Execution {} failed, and it could not be told so; its lease will lapse and recovery will"
					+ " close the row", claimed.id(), whileWriting);
		}
	}

	/**
	 * The attempt could not be recorded, so nothing may run. Either the budget is
	 * spent - a job that keeps killing whoever takes it - or the row is no longer
	 * ours, in which case recovery already dealt with it and it is not our business
	 * to report.
	 */
	private void refuseToStart(ClaimedExecution claimed, ExecutionOwnership ownership) {
		findExecution(claimed.id()).ifPresent(execution -> {
			if (workerId.equals(execution.getClaimedBy())) {
				log.warn("Execution {} exhausted its attempts and will not be started again", claimed.id());

				executionProgressService.fail(ownership, ExecutionMessages.executionInterrupted());
			}
		});
	}

	private void execute(ClaimedExecution claimed, ExecutionOwnership ownership) {
		Execution execution = findExecution(claimed.id()).orElse(null);

		ExecutionJobHandler handler = handlers.get(typeOf(claimed));

		if (execution == null || handler == null) {
			log.warn("Nothing can run execution {} of type {}", claimed.id(), claimed.executionType());

			return;
		}

		try {
			// Between taking the locks and getting here there was a round trip to the
			// database, and a session that died in it would leave this worker holding
			// nothing while believing otherwise. Asked once here so the handler starts
			// on ground that was checked, and again by the handler at each mutation.
			ownership.assertMayGoOnWorking();

			handler.handle(execution, claimed, ownership);
		} catch (OwnershipLostException exception) {
			// Nothing went wrong with the work: the locks under it went away. Saying
			// "error" would blame the job for the moment it was standing in.
			log.warn("Execution {} stopped: {}", claimed.id(), exception.getMessage());

			executionProgressService.interrupt(ownership, ExecutionMessages.executionInterrupted());
		} catch (OperationLockException exception) {
			// A handler can need paths beyond the pair this dispatcher took - an undo
			// reads its movements and locks wherever each file came from. Finding one of
			// those busy is the same answer as finding the first pair busy: wait and be
			// taken again, rather than fail for something that was never about the work.
			log.info("Execution {} handed back: {}", claimed.id(), exception.getMessage());

			handBack(claimed, ownership);
		} catch (RuntimeException exception) {
			// The handler owns its own outcome; reaching here means it did not get to
			// write one, so the dispatcher writes the one nobody else will.
			afterFailure(execution, claimed, ownership, exception);
		}
	}

	/**
	 * What a failure deserves, which is not always an error.
	 *
	 * <p>
	 * A database that was restarting says nothing about the work: the same
	 * execution, run again in a minute, is the one that succeeds. It goes back on
	 * the queue - unless its attempts are spent, because a row returned to a queue
	 * that filters on the attempt budget is a row nobody will ever claim again,
	 * and waiting forever is worse than saying so.
	 */
	private void afterFailure(Execution execution, ClaimedExecution claimed, ExecutionOwnership ownership,
			RuntimeException exception) {
		if (RetryPolicy.worthRetrying(exception) && attemptsRemain(execution)) {
			log.warn("Execution {} hit a passing failure and goes back on the queue", claimed.id(), exception);

			handBack(claimed, ownership);

			return;
		}

		log.error("Execution {} failed", claimed.id(), exception);

		executionProgressService.fail(ownership, ExecutionMessages.inventoryFailed(exception.getMessage()));
	}

	/**
	 * Puts an execution back on the queue, or ends it when there is no queue left
	 * for it to go back to.
	 *
	 * <p>
	 * One request may wait while another of the same runs - that is deliberate -
	 * but two may not wait. So a job handed back while its successor is already
	 * queued has nothing left to add: it is refused rather than cancelled, because
	 * cancelled is what a person's click means and somebody reading the history to
	 * ask whether their cancel worked must not find this instead.
	 */
	private void handBack(ClaimedExecution claimed, ExecutionOwnership ownership) {
		if (executionQueue.release(claimed.id(), workerId, leaseRenewer.claimCountOf(claimed.id()),
				workerProperties.lockBackoffSecondsOrDefault())) {
			return;
		}

		if (!executionQueue.hasWaitingDuplicate(claimed.id())) {
			// The row moved on under us - recovery, a cancel, another worker. Not ours
			// to write an outcome for. It is also the answer for a row that is no longer
			// there at all, so what follows always has one to write to.
			return;
		}

		log.info("Execution {} was superseded by an identical request already waiting", claimed.id());

		executionProgressService.reject(ownership, ExecutionMessages.executionSuperseded());
	}

	private boolean attemptsRemain(Execution execution) {
		Integer attempts = execution.getClaimCount();

		return attempts == null || attempts < workerProperties.maxClaimsOrDefault();
	}

	private ExecutionType typeOf(ClaimedExecution claimed) {
		return ExecutionType.valueOf(claimed.executionType());
	}

	/**
	 * The paths this execution needs to own. Both ends of a move, when there are
	 * two - the destination has to be locked as much as the source.
	 */
	private Path[] pathsOf(ClaimedExecution claimed) {
		return Stream.of(claimed.sourcePath(), claimed.targetPath()).filter(Objects::nonNull)
				.map(PathUtils::normalizePath).toArray(Path[]::new);
	}

	private Optional<Execution> findExecution(long executionId) {
		return executionRepository.findById(executionId);
	}
}