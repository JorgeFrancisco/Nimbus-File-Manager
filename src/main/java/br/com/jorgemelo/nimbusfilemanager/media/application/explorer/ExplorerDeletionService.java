package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.EligibilityAnnouncer;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.media.application.constants.ExplorerMessages;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantineIntakeService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.PreparedMovement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * Carrying out the two shapes of removal the Files screen offers: quarantine
 * (recoverable) and permanent.
 *
 * <p>
 * Both run in the worker, off the queue, with the paths already locked by the
 * dispatcher. Quarantine reuses the same intake the duplicate screen uses -
 * secure move with hash verification plus the movement record that makes a
 * restore possible - so a file removed here is recoverable exactly like one
 * removed there. A file the catalog does not know cannot be recorded, and
 * therefore cannot be restored later; rather than move it into quarantine as an
 * untraceable orphan, it is left alone and counted, and the message says how
 * many stayed behind.
 *
 * <p>
 * Neither honours a cancellation request, and that is a decision rather than an
 * omission. Permanent deletion has no point after which stopping helps: every
 * file it removed is already gone, and a tree half removed is a worse place to
 * be left than either end of it. Quarantine could stop between files, but
 * gaining that with the migration - because a row exists to press a button
 * against - would be changing what the command does on the way past.
 */
@Slf4j
@Service
public class ExplorerDeletionService {

	private final ExplorerDeletionGuard guard;
	private final QuarantineIntakeService quarantineIntakeService;
	private final CatalogFileLocationRepository catalogFileLocationRepository;
	private final ExecutionProgressService executionProgressService;
	private final ExplorerFileSystem fileSystem;
	private final EligibilityAnnouncer eligibilityAnnouncer;
	private final ExplorerDeletionPersistence explorerDeletionPersistence;

	/**
	 * Takes the filesystem as a collaborator for two reasons: the real one
	 * announces every removal to the watcher, so deleting does not wake the
	 * inventory once per file, and a test can hand over a disk that refuses - an
	 * unreadable folder or a file that will not be removed are real branches here,
	 * and no temporary directory can be made to produce them on demand.
	 */
	@Autowired
	ExplorerDeletionService(ExplorerDeletionGuard guard, QuarantineIntakeService quarantineIntakeService,
			CatalogFileLocationRepository catalogFileLocationRepository,
			ExecutionProgressService executionProgressService, ExplorerFileSystem fileSystem,
			EligibilityAnnouncer eligibilityAnnouncer, ExplorerDeletionPersistence explorerDeletionPersistence) {
		this.guard = guard;
		this.quarantineIntakeService = quarantineIntakeService;
		this.catalogFileLocationRepository = catalogFileLocationRepository;
		this.executionProgressService = executionProgressService;
		this.fileSystem = fileSystem;
		this.eligibilityAnnouncer = eligibilityAnnouncer;
		this.explorerDeletionPersistence = explorerDeletionPersistence;
	}

	/**
	 * The quarantine root comes from the execution rather than from the setting.
	 * It is the path the dispatcher locked before this started, and reading the
	 * setting again here could send files into a folder nothing is holding - if
	 * somebody changed it while the request waited.
	 */
	public void quarantine(Execution execution, ExecutionOwnership ownership) {
		Path target = PathUtils.normalizePath(execution.getSourcePath());
		Path quarantineRoot = PathUtils.normalizePath(execution.getTargetPath());

		Optional<ExecutionMessage> refusal = guard.refusal(target);

		if (refusal.isPresent()) {
			executionProgressService.reject(ownership, refusal.get());

			return;
		}

		List<CatalogFile> files = catalogedUnder(target);

		int candidates = countFiles(target);

		if (files.isEmpty()) {
			nothingToQuarantine(execution, ownership, target, candidates);

			return;
		}

		move(execution, ownership, files, target, quarantineRoot, candidates);
	}

	/**
	 * Nothing catalogued can mean two very different things. With files still
	 * there, they are files the catalog never saw and quarantining them would
	 * leave them unrestorable - so they stay, and the refusal says why. With no
	 * file at all, there is nothing to protect: the folder is an empty shell,
	 * often the one a previous quarantine left behind, and refusing to remove it
	 * would leave the user with a folder they cannot delete from the screen that
	 * emptied it.
	 *
	 * <p>
	 * A negative count means the folder could not be listed at all, so its
	 * contents are unknown and removing it is out of the question.
	 */
	private void nothingToQuarantine(Execution execution, ExecutionOwnership ownership, Path target,
			int candidates) {
		if (candidates != 0) {
			executionProgressService.reject(ownership, ExplorerMessages.nothingCatalogued());

			return;
		}

		if (removeEmptyTree(target, execution.getId())) {
			executionProgressService.finishCommand(ownership, ExecutionStatus.FINISHED, ExecutionCounts.one(),
					ExplorerMessages.emptyFolderRemoved());
		} else {
			executionProgressService.reject(ownership, ExplorerMessages.folderNotRemoved());
		}
	}

	private void move(Execution execution, ExecutionOwnership ownership, List<CatalogFile> files, Path target,
			Path quarantineRoot, int candidates) {
		int moved = 0;
		int failed = 0;

		Map<Long, PreparedMovement> operations = quarantineIntakeService.prepare(execution, files, quarantineRoot,
				MovementReason.USER_QUARANTINED);

		for (CatalogFile file : files) {
			ownership.assertMayGoOnWorking();

			switch (quarantineIntakeService.intake(execution, file, quarantineRoot, operations.get(file.getId()),
					execution.getId())) {
			case MOVED -> moved++;
			case SKIPPED -> log.warn("Explorer quarantine skipped catalog file {}", file.getId());
			case ERROR -> failed++;
			}
		}

		int skipped = Math.max(0, candidates - moved - failed);

		// Once for the folder the user removed, however many files it held: each one
		// that moved is now DELETED and out of the analysed set, and a run where none
		// moved changed nothing to announce.
		if (moved > 0) {
			eligibilityAnnouncer.announce("explorer quarantine");
		}

		// The folder is only expected to go when everything in it did; saying it was
		// removed when it is still on screen is worse than saying nothing.
		boolean folderGone = removeEmptyTree(target, execution.getId());

		ExecutionMessage outcome = folderGone || skipped > 0 || failed > 0
				? ExplorerMessages.quarantineDone(moved, skipped, failed)
				: ExplorerMessages.quarantineDoneFolderKept(moved);

		executionProgressService.finishCommand(ownership,
				failed > 0 ? ExecutionStatus.FINISHED_WITH_ERRORS : ExecutionStatus.FINISHED,
				new ExecutionCounts(candidates, moved, skipped, failed), outcome);
	}

	public void deletePermanently(Execution execution, ExecutionOwnership ownership) {
		Path target = PathUtils.normalizePath(execution.getSourcePath());

		Optional<ExecutionMessage> refusal = guard.refusal(target);

		if (refusal.isPresent()) {
			executionProgressService.reject(ownership, refusal.get());

			return;
		}

		List<CatalogFile> cataloged = catalogedUnder(target);

		// The last moment at which stopping costs nothing: after this line files start
		// leaving the disk for good, and no later check can put one back.
		ownership.assertMayGoOnWorking();

		int deleted;

		try {
			deleted = fileSystem.deleteRecursively(target, execution.getId());
		} catch (IOException exception) {
			log.error("Explorer could not delete {}", target, exception);

			executionProgressService.fail(ownership, ExplorerMessages.deleteFailed(exception.getMessage()));

			return;
		}

		// Through the door, so the removal is a fact and not only a column. A file the
		// user deleted months ago is asked about exactly like one that went missing:
		// when it happened, and at whose request - which a status alone cannot answer.
		explorerDeletionPersistence.removed(cataloged);

		// Everything in the list was ACTIVE when it was read - that is what
		// catalogedUnder selects - so a non-empty list is exactly "files left the
		// analysed set", counted once for the whole tree.
		if (!cataloged.isEmpty()) {
			eligibilityAnnouncer.announce("explorer deletion");
		}

		executionProgressService.finishCommand(ownership, ExecutionStatus.FINISHED,
				new ExecutionCounts(deleted, deleted, 0, 0), ExplorerMessages.deleteDone(deleted));
	}

	/**
	 * Catalog entries for the path itself or, when it is a folder, for everything
	 * under it. Matching is by the stored file key, which is the normalized path.
	 */
	private List<CatalogFile> catalogedUnder(Path target) {
		String key = PathUtils.normalize(target);

		if (!fileSystem.isDirectory(target)) {
			// Present, not merely known: only a file actually sitting there is the one
			// the user asked to remove.
			return catalogFileLocationRepository.findPresentByPath(key, PathFlavor.of(target).name()).stream()
					.toList();
		}

		String prefix = key.endsWith(File.separator) ? key : key + File.separator;

		String flavor = PathFlavor.of(target).name();

		return listFiles(target).stream().map(PathUtils::normalize).filter(path -> path.startsWith(prefix))
				.map(path -> catalogFileLocationRepository.findPresentByPath(path, flavor)).flatMap(Optional::stream)
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
	private boolean removeEmptyTree(Path target, Long executionId) {
		if (!fileSystem.isDirectory(target)) {
			return true;
		}

		try {
			fileSystem.deleteEmptyTree(target, executionId);

			return true;
		} catch (IOException e) {
			log.warn("Explorer left {} in place after quarantining its contents: {}", target, e.getMessage());

			return false;
		}
	}
}