package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCompletionWait;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationPathKey;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.media.application.constants.ExplorerMessages;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.ExplorerActionResult;
import br.com.jorgemelo.nimbusfilemanager.settings.application.QuarantineFolderPolicy;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;
import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerAvailability;

/**
 * What the Files screen's menu asks for, and how it is answered.
 *
 * <p>
 * Each of the three commands is written to the queue as a row and carried out
 * by the worker. This waits a short while for it - long enough that a rename of
 * one file, with the worker idle, answers the way it always did, and short
 * enough that nobody watches a screen think. Past that the answer changes but
 * the work does not: the row is durable, the worker still has it, and the
 * screen is told to expect it rather than told it failed.
 *
 * <p>
 * What is decided here is only what can be decided from a reading: whether the
 * path may be written to at all, whether the new name is a name, whether there
 * is a quarantine folder configured. Those are answered while somebody is
 * looking, with the same sentence as before. Everything that depends on the
 * state of the disk at the moment of acting is checked again by the worker,
 * under the locks, because that is the only look that can be acted on.
 */
@Service
public class ExplorerCommandLauncher extends LocalizedComponent {

	/**
	 * How long the answer waits for the work. A budget for the response, never a
	 * limit on the execution: nothing is cancelled, failed or requeued when it runs
	 * out, and the only thing that changes is what this method returns.
	 *
	 * <p>
	 * One second, because the wake-up means an idle worker starts within
	 * milliseconds of the row being committed - so this covers the ordinary case
	 * whole, and anything it does not cover was never going to be instant.
	 */
	private static final Duration RESPONSE_BUDGET = Duration.ofSeconds(1);

	/** Characters Windows forbids in a name, plus the path separators. */
	private static final Pattern INVALID_NAME = Pattern.compile("[\\\\/:*?\"<>|]");

	private final ExplorerDeletionGuard guard;
	private final QuarantineFolderPolicy quarantineFolderPolicy;
	private final ExecutionEnqueueService executionEnqueueService;
	private final ExecutionCompletionWait executionCompletionWait;
	private final ExecutionMapper executionMapper;
	private final WorkerAvailability workerAvailability;

	public ExplorerCommandLauncher(ExplorerDeletionGuard guard, QuarantineFolderPolicy quarantineFolderPolicy,
			ExecutionEnqueueService executionEnqueueService, ExecutionCompletionWait executionCompletionWait,
			ExecutionMapper executionMapper, WorkerAvailability workerAvailability) {
		this.guard = guard;
		this.quarantineFolderPolicy = quarantineFolderPolicy;
		this.executionEnqueueService = executionEnqueueService;
		this.executionCompletionWait = executionCompletionWait;
		this.executionMapper = executionMapper;
		this.workerAvailability = workerAvailability;
	}

	/**
	 * The deduplication key is the source together with the new name, and both
	 * halves earn their place: two clicks asking for the same rename are the same
	 * request and collapse into one, while renaming the same file to two different
	 * names is two requests, and collapsing those would silently drop one of them.
	 */
	public ExplorerActionResult rename(Path path, String newName) {
		Path source = PathUtils.normalizePath(path.toString());

		Optional<ExecutionMessage> refusal = guard.refusal(source);

		if (refusal.isPresent()) {
			return refused(refusal.get());
		}

		String trimmed = newName == null ? "" : newName.trim();

		Path parent = source.getParent();

		if (trimmed.isBlank() || INVALID_NAME.matcher(trimmed).find() || parent == null) {
			return refused(ExplorerMessages.renameInvalidName());
		}

		Path target = parent.resolve(trimmed).normalize();

		if (Files.exists(target)) {
			return refused(ExplorerMessages.renameTargetExists(trimmed));
		}

		return submit(command(ExecutionType.EXPLORER_RENAME, source, target, ExplorerMessages.RENAME_STARTED)
				.dedupKey(OperationPathKey.canonical(source) + ">" + trimmed).build());
	}

	/**
	 * The quarantine folder goes in the target column because it is the other end
	 * the work needs held: files are moved into it while this runs, and a purge
	 * emptying it at the same time would be two operations writing one tree.
	 */
	public ExplorerActionResult quarantine(Path path) {
		Path target = PathUtils.normalizePath(path.toString());

		Optional<ExecutionMessage> refusal = guard.refusal(target);

		if (refusal.isPresent()) {
			return refused(refusal.get());
		}

		Optional<Path> quarantineRoot = quarantineFolderPolicy.root();

		if (quarantineRoot.isEmpty()) {
			return refused(ExplorerMessages.quarantineNotConfigured());
		}

		return submit(command(ExecutionType.EXPLORER_QUARANTINE, target, quarantineRoot.get(),
				ExplorerMessages.QUARANTINE_STARTED).dedupKey(OperationPathKey.canonical(target)).build());
	}

	public ExplorerActionResult deletePermanently(Path path) {
		Path target = PathUtils.normalizePath(path.toString());

		Optional<ExecutionMessage> refusal = guard.refusal(target);

		if (refusal.isPresent()) {
			return refused(refusal.get());
		}

		return submit(command(ExecutionType.EXPLORER_DELETE, target, null, ExplorerMessages.DELETE_STARTED)
				.dedupKey(OperationPathKey.canonical(target)).build());
	}

	private Execution.ExecutionBuilder command(ExecutionType type, Path source, Path target, String startedCode) {
		return Execution.builder().executionType(type).sourcePath(PathUtils.normalize(source))
				.targetPath(target == null ? null : PathUtils.normalize(target)).recursive(false).executeFlag(true)
				.statusMessage(StatusMessage.code(startedCode));
	}

	private ExplorerActionResult submit(Execution request) {
		Execution queued = executionEnqueueService.enqueueOrExisting(request);

		return executionCompletionWait.awaitTerminal(queued.getId(), RESPONSE_BUDGET).map(this::outcome)
				.orElseGet(() -> stillGoing(queued));
	}

	/**
	 * The row read back as the dialog's sentence. Only FINISHED is success:
	 * finished-with-errors and rejected both mean the person has to be told
	 * something, and the message they carry is the telling.
	 */
	private ExplorerActionResult outcome(Execution execution) {
		ExecutionResponse response = executionMapper.toResponse(execution);

		return ExplorerActionResult.of(execution.getStatus() == ExecutionStatus.FINISHED, response.message(),
				response.filesMoved(), response.cacheHits(), response.errors());
	}

	/**
	 * Past the budget, and the difference between the two sentences matters: one
	 * says something is working on it, the other says nothing is - yet. Neither is
	 * a refusal, and the absence of a worker is never a reason to do the work here
	 * instead.
	 */
	private ExplorerActionResult stillGoing(Execution queued) {
		ExecutionMessage message = workerAvailability.current().available() ? ExplorerMessages.stillProcessing()
				: ExplorerMessages.waitingForWorker();

		return ExplorerActionResult.pending(text(message), UuidV7.orLegacy(queued.getPublicId(), queued.getId()));
	}

	private ExplorerActionResult refused(ExecutionMessage message) {
		return ExplorerActionResult.refused(text(message));
	}

	private String text(ExecutionMessage message) {
		return message(message.code(), message.args().toArray());
	}
}