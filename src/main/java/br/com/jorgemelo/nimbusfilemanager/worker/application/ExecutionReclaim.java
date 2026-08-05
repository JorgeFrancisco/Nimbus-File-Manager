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
	 * One pass, before this worker starts claiming.
	 *
	 * @return how many executions were dealt with, for the log and for the test
	 * that would otherwise have to guess
	 */
	public int reclaimAbandoned() {
		List<Long> abandoned = executionQueue.expiredLeases();

		if (abandoned.isEmpty()) {
			return 0;
		}

		log.info("Reclaiming {} execution(s) whose worker stopped renewing", abandoned.size());

		abandoned.forEach(this::reclaim);

		return abandoned.size();
	}

	private void reclaim(long executionId) {
		Optional<Execution> found = executionRepository.findById(executionId);

		if (found.isEmpty()) {
			return;
		}

		Execution execution = found.get();

		if (attemptsSpent(execution)) {
			log.warn("Execution {} was abandoned with no attempts left and will not be started again", executionId);

			executionProgressService.fail(execution, ExecutionMessages.executionInterrupted());

			return;
		}

		if (resumable(execution)) {
			requeueOrReject(execution);

			return;
		}

		executionProgressService.interrupt(execution, ExecutionMessages.executionInterrupted());

		reconcileWhatItWasTouching(execution);
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
	 */
	private void requeueOrReject(Execution execution) {
		if (executionQueue.requeue(execution.getId())) {
			return;
		}

		if (executionQueue.hasWaitingDuplicate(execution.getId())) {
			executionProgressService.reject(execution, ExecutionMessages.executionSuperseded());
		}
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