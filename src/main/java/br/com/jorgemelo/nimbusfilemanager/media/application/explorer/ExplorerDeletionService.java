package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.ExplorerActionResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantineIntakeService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.StatusMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * Deleting from the file explorer, in the two shapes the dialog offers:
 * quarantine (recoverable) and permanent.
 *
 * <p>
 * Quarantine reuses the same intake the duplicate screen uses - secure move
 * with hash verification plus the movement record that makes a restore possible
 * - so a file removed here is recoverable exactly like one removed there. A
 * file the catalog does not know cannot be recorded, and therefore cannot be
 * restored later; rather than move it into quarantine as an untraceable orphan,
 * it is left alone and counted, and the message says how many stayed behind.
 */
@Slf4j
@Service
public class ExplorerDeletionService extends LocalizedComponent {

	/**
	 * How long a click waits for a background pass to release the path. The
	 * inventory watches the whole library, so a scheduled run overlaps every folder
	 * in it; failing on the first look made the menu answer "busy" for something
	 * the user cannot see, seconds before the path was free again. Short enough
	 * that a genuinely long operation still answers quickly instead of hanging the
	 * dialog.
	 */
	private static final Duration LOCK_WAIT = Duration.ofSeconds(20);

	private final ExplorerDeletionGuard guard;
	private final QuarantineIntakeService quarantineIntakeService;
	private final CatalogFileRepository catalogFileRepository;
	private final ExecutionRepository executionRepository;
	private final OperationLockService operationLockService;
	private final Clock clock;
	private final ExplorerFileSystem fileSystem;

	/**
	 * Takes the filesystem as a collaborator for two reasons: the real one
	 * announces every removal to the watcher, so deleting does not wake the
	 * inventory once per file, and a test can hand over a disk that refuses - an
	 * unreadable folder or a file that will not be removed are real branches here,
	 * and no temporary directory can be made to produce them on demand.
	 */
	@Autowired
	ExplorerDeletionService(ExplorerDeletionGuard guard, QuarantineIntakeService quarantineIntakeService,
			CatalogFileRepository catalogFileRepository, ExecutionRepository executionRepository,
			OperationLockService operationLockService, Clock clock, ExplorerFileSystem fileSystem) {
		this.guard = guard;
		this.quarantineIntakeService = quarantineIntakeService;
		this.catalogFileRepository = catalogFileRepository;
		this.executionRepository = executionRepository;
		this.operationLockService = operationLockService;
		this.clock = clock;
		this.fileSystem = fileSystem;
	}

	@Transactional
	public ExplorerActionResult quarantine(Path path) {
		Path target = PathUtils.normalizePath(path.toString());

		Optional<String> refusal = guard.refusal(target);

		if (refusal.isPresent()) {
			return ExplorerActionResult.refused(refusal.get());
		}

		Optional<Path> quarantineRoot = quarantineIntakeService.root();

		if (quarantineRoot.isEmpty()) {
			return ExplorerActionResult.refused(message("backend.files.quarantineNotConfigured"));
		}

		try (var _ = operationLockService.acquireWithin(LOCK_WAIT, ExecutionType.DEDUP_DELETE, target)) {
			return quarantineLocked(target, quarantineRoot.get());
		} catch (OperationLockException e) {
			log.warn("Explorer quarantine blocked because another operation is using {}: {}", target, e.getMessage());

			return ExplorerActionResult.refused(message("backend.files.busy"));
		}
	}

	private ExplorerActionResult quarantineLocked(Path target, Path quarantineRoot) {
		List<CatalogFile> files = catalogedUnder(target);

		int candidates = countFiles(target);

		if (files.isEmpty()) {
			// Nothing catalogued can mean two very different things. With files still
			// there, they are files the catalog never saw and quarantining them would
			// leave them unrestorable - so they stay, and the refusal says why. With no
			// file at all, there is nothing to protect: the folder is an empty shell,
			// often the one a previous quarantine left behind, and refusing to remove it
			// would leave the user with a folder they cannot delete from the screen that
			// emptied it.
			// A negative count means the folder could not be listed at all, so its
			// contents are unknown and removing it is out of the question.
			if (candidates != 0) {
				return ExplorerActionResult.refused(message("backend.files.quarantineNothingCataloged"));
			}

			return removeEmptyTree(target) ? ExplorerActionResult.of(message("backend.files.emptyFolderRemoved"))
					: ExplorerActionResult.refused(message("backend.files.folderNotRemoved"));
		}

		Execution execution = startExecution(quarantineRoot);

		int moved = 0;
		int failed = 0;

		for (CatalogFile file : files) {
			switch (quarantineIntakeService.intake(execution, file, quarantineRoot, MovementReason.USER_QUARANTINED)) {
			case MOVED -> moved++;
			case SKIPPED -> log.warn("Explorer quarantine skipped catalog file {}", file.getId());
			case ERROR -> failed++;
			}
		}

		int skipped = Math.max(0, candidates - moved - failed);

		// The folder is only expected to go when everything in it did; saying it was
		// removed when it is still on screen is worse than saying nothing.
		boolean folderGone = removeEmptyTree(target);

		String outcome = folderGone || skipped > 0 || failed > 0
				? message("backend.files.quarantineDone", moved, skipped, failed)
				: message("backend.files.quarantineDoneFolderKept", moved);

		finishExecution(execution, moved, failed, outcome);

		return new ExplorerActionResult(failed == 0, outcome, moved, skipped, failed);
	}

	@Transactional
	public ExplorerActionResult deletePermanently(Path path) {
		Path target = PathUtils.normalizePath(path.toString());

		Optional<String> refusal = guard.refusal(target);

		if (refusal.isPresent()) {
			return ExplorerActionResult.refused(refusal.get());
		}

		try (var _ = operationLockService.acquireWithin(LOCK_WAIT, ExecutionType.QUARANTINE_PURGE, target)) {
			return deleteLocked(target);
		} catch (OperationLockException e) {
			log.warn("Explorer deletion blocked because another operation is using {}: {}", target, e.getMessage());

			return ExplorerActionResult.refused(message("backend.files.busy"));
		}
	}

	private ExplorerActionResult deleteLocked(Path target) {
		List<CatalogFile> cataloged = catalogedUnder(target);

		int deleted;

		try {
			deleted = fileSystem.deleteRecursively(target);
		} catch (IOException e) {
			log.error("Explorer could not delete {}", target, e);

			return ExplorerActionResult.refused(message("backend.files.deleteFailed", e.getMessage()));
		}

		cataloged.forEach(file -> file.setLifecycleStatus(LifecycleStatus.DELETED));

		catalogFileRepository.saveAll(cataloged);

		return new ExplorerActionResult(true, message("backend.files.deleteDone", deleted), deleted, 0, 0);
	}

	/**
	 * Catalog entries for the path itself or, when it is a folder, for everything
	 * under it. Matching is by the stored file key, which is the normalized path.
	 */
	private List<CatalogFile> catalogedUnder(Path target) {
		String key = PathUtils.normalize(target);

		if (!fileSystem.isDirectory(target)) {
			return catalogFileRepository.findByFileKey(key).filter(CatalogFile::isActive).stream().toList();
		}

		String prefix = key.endsWith(File.separator) ? key : key + File.separator;

		return listFiles(target).stream().map(PathUtils::normalize).filter(path -> path.startsWith(prefix))
				.map(catalogFileRepository::findByFileKey).flatMap(Optional::stream).filter(CatalogFile::isActive)
				.toList();
	}

	/**
	 * The files under {@code folder}, or empty when the folder could not be listed.
	 * The distinction matters: an unreadable folder and an empty one both yield no
	 * files, and treating the first as the second would delete a folder whose
	 * contents are simply unknown.
	 */
	private Optional<List<Path>> filesUnder(Path folder) {
		try {
			return Optional.of(fileSystem.listFiles(folder));
		} catch (IOException e) {
			log.warn("Explorer could not list {} while preparing a deletion: {}", folder, e.getMessage());

			return Optional.empty();
		}
	}

	private List<Path> listFiles(Path folder) {
		return filesUnder(folder).orElseGet(List::of);
	}

	/**
	 * How many files the path holds, or -1 when a folder could not be listed - the
	 * caller must not read that as "nothing here".
	 */
	private int countFiles(Path target) {
		if (!fileSystem.isDirectory(target)) {
			return 1;
		}

		return filesUnder(target).map(List::size).orElse(-1);
	}

	/**
	 * Whether the emptied folder is gone. Returning the outcome instead of
	 * swallowing it: the dialog used to announce "empty folder removed" while the
	 * folder was still on screen, because a failure here only reached the log.
	 */
	private boolean removeEmptyTree(Path target) {
		if (!fileSystem.isDirectory(target)) {
			return true;
		}

		try {
			fileSystem.deleteEmptyTree(target);

			return true;
		} catch (IOException e) {
			log.warn("Explorer left {} in place after quarantining its contents: {}", target, e.getMessage());

			return false;
		}
	}

	private Execution startExecution(Path quarantineRoot) {
		Execution execution = Execution.builder().executionType(ExecutionType.DEDUP_DELETE)
				.status(ExecutionStatus.STARTED).startedAt(LocalDateTime.now(clock))
				.sourcePath(PathUtils.normalize(quarantineRoot)).targetPath(PathUtils.normalize(quarantineRoot))
				.recursive(false).executeFlag(true)
				.statusMessage(StatusMessage.raw(message("backend.files.quarantineStarted"))).filesFound(0)
				.filesAnalyzed(0).cacheHits(0).filesMoved(0).simulatedFiles(0).errors(0).build();

		return executionRepository.save(execution);
	}

	private void finishExecution(Execution execution, int moved, int failed, String statusMessage) {
		execution.setStatus(failed > 0 ? ExecutionStatus.FINISHED_WITH_ERRORS : ExecutionStatus.FINISHED);
		execution.setFinishedAt(LocalDateTime.now(clock));
		execution.setFilesMoved(moved);
		execution.setErrors(failed);
		execution.setStatusMessage(StatusMessage.raw(statusMessage));

		executionRepository.save(execution);
	}
}