package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.EligibilityAnnouncer;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.media.application.constants.ExplorerMessages;
import br.com.jorgemelo.nimbusfilemanager.shared.application.catalog.CatalogMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.application.library.LibraryFileMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * Carrying out a rename asked for from the Files screen.
 *
 * <p>
 * It runs in the worker, off the queue, with the path locks for both ends
 * already held by the dispatcher - which is why there is no lock here and no
 * waiting for one. The rename is a move like any other, so it goes through
 * {@link LibraryFileMutations} (hash baseline, byte-for-byte verify, roll-back)
 * rather than {@code Files.move}: it is the user's own media, and the project
 * reserves the plain move for regenerable artefacts.
 *
 * <p>
 * Everything is checked again here, and that is not belt and braces. Between
 * the click that queued this and the moment it runs, the file may have been
 * moved, deleted, or the name taken by something else - and the second look is
 * the only one made under the locks.
 *
 * <p>
 * The catalog is corrected in the same operation, not left to the next
 * reconciliation. A folder rename moves everything under it in one call, and
 * until the catalog knows, every screen reads the collection as a folder full
 * of missing files.
 */
@Slf4j
@Service
public class ExplorerRenameService {

	private final ExplorerDeletionGuard guard;
	private final LibraryFileMutations libraryFileMutations;
	private final ExplorerRenamePersistence explorerRenamePersistence;
	private final CatalogMutations catalogMutations;
	private final ExecutionProgressService executionProgressService;
	private final EligibilityAnnouncer eligibilityAnnouncer;
	private final Clock clock;

	public ExplorerRenameService(ExplorerDeletionGuard guard, LibraryFileMutations libraryFileMutations,
			ExplorerRenamePersistence explorerRenamePersistence, CatalogMutations catalogMutations,
			ExecutionProgressService executionProgressService, EligibilityAnnouncer eligibilityAnnouncer, Clock clock) {
		this.guard = guard;
		this.libraryFileMutations = libraryFileMutations;
		this.explorerRenamePersistence = explorerRenamePersistence;
		this.catalogMutations = catalogMutations;
		this.executionProgressService = executionProgressService;
		this.eligibilityAnnouncer = eligibilityAnnouncer;
		this.clock = clock;
	}

	public void execute(Execution execution, ExecutionOwnership ownership) {
		Path source = PathUtils.normalizePath(execution.getSourcePath());
		Path target = PathUtils.normalizePath(execution.getTargetPath());

		String newName = target.getFileName().toString();

		Optional<ExecutionMessage> refusal = guard.refusal(source);

		if (refusal.isPresent()) {
			executionProgressService.reject(ownership, refusal.get());

			return;
		}

		if (Files.exists(target)) {
			executionProgressService.reject(ownership, ExplorerMessages.renameTargetExists(newName));

			return;
		}

		boolean directory = Files.isDirectory(source);

		// Asked immediately before the irreversible part, which is the contract every
		// handler that touches the user's files keeps: the locks can go away while the
		// work is in flight, and nothing may be written after they have.
		ownership.assertMayGoOnWorking();

		if (!move(execution, ownership, source, target, directory)) {
			return;
		}

		repointCatalog(execution, source, target, directory);

		executionProgressService.finishCommand(ownership, ExecutionStatus.FINISHED, ExecutionCounts.one(),
				ExplorerMessages.renameDone(newName));
	}

	/** @return whether the rename happened; a failure has already been reported */
	private boolean move(Execution execution, ExecutionOwnership ownership, Path source, Path target,
			boolean directory) {
		try {
			if (directory) {
				// A folder has no bytes of its own to verify, so the port offers it as its
				// own operation - and announces both names to the watcher there, under this
				// execution, which is what keeps the notifications for everything inside it
				// from reading as changes from outside.
				libraryFileMutations.renameDirectory(source, target, execution.getId());
			} else {
				libraryFileMutations.move(source, target, false, execution.getId());
			}

			return true;
		} catch (IOException exception) {
			log.error("Explorer could not rename {} to {}", source, target, exception);

			executionProgressService.fail(ownership, ExplorerMessages.renameFailed(exception.getMessage()));

			return false;
		}
	}

	/**
	 * Renaming a file is invisible to a duplicate analysis and renaming a folder
	 * need not be. What decides who may be analysed is the folder a file sits in,
	 * and a file rename leaves that alone - {@code current_folder} is not among the
	 * columns the single-file path writes - so nothing is announced for one. A
	 * folder rename moves every file under it to a different folder, which changes
	 * that column for all of them at once, and is announced when the folders
	 * involved are ones the exclusion list has an opinion about.
	 */
	private void repointCatalog(Execution execution, Path source, Path target, boolean directory) {
		if (!directory) {
			explorerRenamePersistence.rename(source, target);

			return;
		}

		int repointed = catalogMutations.repointFolder(PathUtils.normalize(source), PathUtils.normalize(target),
				LocalDateTime.now(clock));

		log.info("Execution {} renamed a folder and moved {} catalogued file(s) with it", execution.getId(),
				repointed);

		if (repointed > 0 && eligibilityAnnouncer.repointCanChangeEligibility(PathUtils.normalize(source),
				PathUtils.normalize(target))) {
			eligibilityAnnouncer.announce("explorer folder rename");
		}
	}
}