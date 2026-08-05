package br.com.jorgemelo.nimbusfilemanager.worker.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionJobHandler;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationPathKey;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence.ExecutionQueue;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Takes back the work a dead worker left behind - the one recovery there is.
 *
 * <p>
 * It used to be one of two. The application had its own, asked with a wider
 * question ("which executions does nobody hold a lease on?") because there were
 * runs that never had a lease: the engine still ran in the application, and a
 * worker must not requeue those. There are none left. Every execution reaches
 * RUNNING through the claim, which writes the lease in the same statement, so
 * the wide question and the narrow one now name the same rows - and two policies
 * over the same rows is not redundancy, it is whichever process starts first
 * winning. The application's marked everything interrupted and queued no
 * reconcile, so a restart in the wrong order left a divergence nobody would
 * repair.
 *
 * <p>
 * So both roles run this, at their own start. Doing it twice is safe by
 * construction: the requeue is conditional on the row still being the one that
 * was read, and the reconcile it enqueues is deduplicated by folder.
 *
 * <p>
 * What each one deserves depends on what it was doing. A pass that reads and
 * reconciles can simply be run again. Anything that moves or deletes files
 * cannot: half of it already happened, and a second run would start from a world
 * the first one changed - so it is closed as interrupted and the divergence is
 * left to reconciliation.
 *
 * <p>
 * An execution that has spent its attempts ends here for good. Returning it to
 * the queue would be returning it invisible - the claim filters on the attempt
 * budget - and a row waiting forever for a worker that is not allowed to take it
 * is the quietest kind of lost work.
 */
@Slf4j
@Component
public class ExecutionReclaim {

	private final ExecutionQueue executionQueue;
	private final ExecutionRepository executionRepository;
	private final ExecutionProgressService executionProgressService;
	private final ExecutionEnqueueService executionEnqueueService;
	private final WorkerProperties workerProperties;
	private final Map<ExecutionType, ExecutionJobHandler> handlers;

	public ExecutionReclaim(ExecutionQueue executionQueue, ExecutionRepository executionRepository,
			ExecutionProgressService executionProgressService, ExecutionEnqueueService executionEnqueueService,
			WorkerProperties workerProperties, List<ExecutionJobHandler> handlers) {
		this.executionQueue = executionQueue;
		this.executionRepository = executionRepository;
		this.executionProgressService = executionProgressService;
		this.executionEnqueueService = executionEnqueueService;
		this.workerProperties = workerProperties;
		this.handlers = handlers.stream().collect(Collectors.toMap(ExecutionJobHandler::type, Function.identity()));
	}

	/**
	 * One pass over whatever nobody is renewing.
	 *
	 * <p>
	 * Called at the start of both roles and, from the worker, on a timer for as
	 * long as it runs - one rule, asked repeatedly, rather than a startup policy
	 * and a runtime one that could drift apart. Nothing here assumes it is alone
	 * or that it is early: every write states the condition it depends on, so a
	 * pass that loses a race simply finds nothing to do.
	 *
	 * @return how many executions this pass recovered, for the log and for the
	 * test that would otherwise have to guess
	 */
	public int reclaimAbandoned() {
		int reclaimed = 0;

		for (long executionId : executionQueue.expiredLeases()) {
			reclaimed += reclaim(executionId) ? 1 : 0;
		}

		// Counted after the fact and said only when there was something to say. The
		// pass runs on a timer now, so announcing every empty round would bury the
		// rounds that matter - and announcing the candidates instead of the winners
		// would report work that a completion or another reclaimer took first.
		if (reclaimed > 0) {
			log.info("Reclaimed {} execution(s) whose worker stopped renewing", reclaimed);
		}

		return reclaimed;
	}

	/**
	 * @return whether this pass is the one that recovered the execution. False
	 * covers both "there was nothing left to recover" and "somebody else got there
	 * first", which are the same thing to a caller: not ours to write an outcome
	 * for.
	 */
	private boolean reclaim(long executionId) {
		Optional<Execution> found = executionRepository.findById(executionId);

		if (found.isEmpty()) {
			return false;
		}

		Execution execution = found.get();

		if (attemptsSpent(execution)) {
			if (!executionProgressService.failAbandoned(execution, ExecutionMessages.executionInterrupted())) {
				return false;
			}

			log.warn("Execution {} was abandoned with no attempts left and will not be started again", executionId);

			return true;
		}

		if (resumable(execution)) {
			return requeueOrReject(execution);
		}

		if (!executionProgressService.interruptAbandoned(execution, ExecutionMessages.executionInterrupted())) {
			return false;
		}

		reconcileWhatItWasTouching(execution);

		return true;
	}

	/**
	 * A run that moves files and stopped without being able to say so can leave the
	 * library and the catalog disagreeing - a file that reached its destination in
	 * the moment before the process went, with the row that describes it never
	 * written. Marking the execution interrupted records that; it does not repair
	 * it.
	 *
	 * <p>
	 * Queued for both ends because the divergence looks different at each: at the
	 * source a catalogued file is no longer where the catalog says, at the target a
	 * file exists that nobody catalogued. Deduplication makes a repeat harmless -
	 * one waiting per folder is all the queue will hold.
	 */
	private void reconcileWhatItWasTouching(Execution execution) {
		Stream.of(execution.getSourcePath(), execution.getTargetPath()).filter(Objects::nonNull)
				.map(ExecutionReclaim::folderOf).flatMap(Optional::stream).map(Path::toString).distinct()
				.forEach(folder -> reconcile(folder, Boolean.TRUE.equals(execution.getRecursive())));
	}

	/**
	 * The folder to reconcile for a path an execution was working on.
	 *
	 * <p>
	 * Not the path itself, because a path column does not always hold a folder:
	 * the commands the Files screen queues name one file, and by the time anybody
	 * reclaims them that file may be exactly what is no longer there. Reconciling
	 * asks a question about a folder, so the walk goes up until it finds one that
	 * still exists - which, for a file that was renamed or deleted, is the folder
	 * the divergence is in anyway.
	 */
	private static Optional<Path> folderOf(String path) {
		for (Path current = Path.of(path); current != null; current = current.getParent()) {
			if (Files.isDirectory(current)) {
				return Optional.of(current);
			}
		}

		return Optional.empty();
	}

	private void reconcile(String folder, boolean recursive) {
		executionEnqueueService.enqueue(Execution.builder().executionType(ExecutionType.RECONCILE).sourcePath(folder)
				.recursive(recursive).executeFlag(true).dedupKey(OperationPathKey.canonical(Path.of(folder)))
				.statusMessage(StatusMessage.code(ExecutionMessages.RECONCILE_REPAIRED)).build());
	}

	/**
	 * Abandoned work goes back on the queue - unless an identical request is
	 * already waiting there, in which case putting it back would mean two of the
	 * same waiting, which the queue forbids and which would add nothing anyway.
	 * Refused rather than cancelled: nobody asked for this to stop.
	 *
	 * <p>
	 * A requeue that changed nothing is not evidence of a successor. It is also
	 * what a completion, a cancellation, a renewed lease and another reclaimer all
	 * look like from here, and calling any of those "superseded" would write a
	 * refusal over an outcome that was already reached. So the successor is
	 * confirmed first, and even then the refusal is applied only if the row is
	 * still the abandoned one - two conditions, because the answer to the first
	 * can stop being true while the second is being asked.
	 */
	private boolean requeueOrReject(Execution execution) {
		if (executionQueue.requeue(execution.getId())) {
			return true;
		}

		if (!executionQueue.hasWaitingDuplicate(execution.getId())) {
			return false;
		}

		return executionProgressService.rejectSuperseded(execution, ExecutionMessages.executionSuperseded());
	}

	private boolean attemptsSpent(Execution execution) {
		Integer attempts = execution.getClaimCount();

		return attempts != null && attempts >= workerProperties.maxClaimsOrDefault();
	}

	/**
	 * A type this worker has no handler for is not one it can judge, so it gets
	 * the answer that cannot corrupt anything.
	 */
	private boolean resumable(Execution execution) {
		ExecutionJobHandler handler = handlers.get(execution.getExecutionType());

		return handler != null && handler.resumable();
	}
}