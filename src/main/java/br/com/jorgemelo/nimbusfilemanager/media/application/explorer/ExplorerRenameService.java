package br.com.jorgemelo.nimbusfilemanager.media.application.explorer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.EligibilityAnnouncer;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.media.application.constants.ExplorerMessages;
import br.com.jorgemelo.nimbusfilemanager.media.application.explorer.dto.ExplorerMove;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MoveBaseline;
import br.com.jorgemelo.nimbusfilemanager.shared.application.MovementRecovery;
import br.com.jorgemelo.nimbusfilemanager.shared.application.catalog.CatalogConvergenceMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.CatalogFactProvenance;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.PreparedMovement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventEvidence;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
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
	private final ExplorerRelocationPlan explorerRelocationPlan;
	private final CatalogConvergenceMutations catalogMutations;
	private final ExecutionProgressService executionProgressService;
	private final EligibilityAnnouncer eligibilityAnnouncer;
	private final Clock clock;

	public ExplorerRenameService(ExplorerDeletionGuard guard, LibraryFileMutations libraryFileMutations,
			ExplorerRenamePersistence explorerRenamePersistence, ExplorerRelocationPlan explorerRelocationPlan,
			CatalogConvergenceMutations catalogMutations, ExecutionProgressService executionProgressService,
			EligibilityAnnouncer eligibilityAnnouncer, Clock clock) {
		this.guard = guard;
		this.libraryFileMutations = libraryFileMutations;
		this.explorerRenamePersistence = explorerRenamePersistence;
		this.explorerRelocationPlan = explorerRelocationPlan;
		this.catalogMutations = catalogMutations;
		this.executionProgressService = executionProgressService;
		this.eligibilityAnnouncer = eligibilityAnnouncer;
		this.clock = clock;
	}

	public void execute(Execution execution, ExecutionOwnership ownership) {
		Path source = PathUtils.normalizePath(execution.getSourcePath());
		Path target = PathUtils.normalizePath(execution.getTargetPath());

		String newName = target.getFileName().toString();

		// Asked of whichever end is there. After a crash the source is gone, and a
		// folder that has already moved would be taken for a file.
		boolean directory = Files.exists(source) ? Files.isDirectory(source) : Files.isDirectory(target);

		// Every catalogued file this will move, and the identity of the fact each of
		// them will produce, written down before anything moves. A crash between the
		// disk and the database leaves those rows behind, which is how the retry knows
		// what it was in the middle of instead of concluding it from what it finds.
		List<PreparedMovement> reserved = explorerRelocationPlan.reserve(execution, source, target, directory);

		boolean alreadyRenamed = alreadyRenamed(reserved, source, target);

		if (!alreadyRenamed) {
			Optional<ExecutionMessage> refusal = guard.refusal(source);

			if (refusal.isPresent()) {
				executionProgressService.reject(ownership, refusal.get());

				return;
			}

			if (Files.exists(target)) {
				executionProgressService.reject(ownership, ExplorerMessages.renameTargetExists(newName));

				return;
			}
		}

		// Asked immediately before the irreversible part, which is the contract every
		// handler that touches the user's files keeps: the locks can go away while the
		// work is in flight, and nothing may be written after they have.
		ownership.assertMayGoOnWorking();

		// A retry finishes what is left of the operation rather than performing it
		// again: the bytes are already at the destination, and moving them a second
		// time would move whatever is at the source now - which is nothing.
		ExplorerMove moved = alreadyRenamed ? new ExplorerMove(true, null)
				: move(execution, ownership, source, target, directory);

		if (!moved.done()) {
			explorerRelocationPlan.abandon(execution, reserved, MovementReason.IO_ERROR);

			return;
		}

		repointCatalog(execution, source, target, directory, moved.baseline(), reserved);

		explorerRelocationPlan.settle(execution, reserved);

		executionProgressService.finishCommand(ownership, ExecutionStatus.FINISHED, ExecutionCounts.one(),
				ExplorerMessages.renameDone(newName));
	}

	/**
	 * Whether this run's own effect is what is at the destination.
	 *
	 * <p>
	 * The disk on its own cannot say: a source that is gone and a destination that
	 * is there is what a finished rename looks like, and also what somebody else's
	 * file at a name we were asked to write to looks like. The operations this
	 * execution reserved are what tell the two apart - and only when every one of
	 * them reads as this operation, because an attempt that was abandoned or
	 * refused is a decision, not work waiting to be finished.
	 */
	private boolean alreadyRenamed(List<PreparedMovement> reserved, Path source, Path target) {
		if (reserved.isEmpty() || Files.exists(source) || !Files.exists(target)) {
			return false;
		}

		return reserved.stream().allMatch(operation -> switch (MovementRecovery.progressOf(operation, source, target)) {
		case RESUME, ALREADY_DONE -> true;
		case EXECUTE, REFUSE -> false;
		});
	}

	/**
	 * @return whether it happened and what it proved; a failure has already been
	 * reported by the time this returns
	 */
	private ExplorerMove move(Execution execution, ExecutionOwnership ownership, Path source, Path target,
			boolean directory) {
		try {
			if (directory) {
				// A folder has no bytes of its own to verify, so the port offers it as its
				// own operation - and announces both names to the watcher there, under this
				// execution, which is what keeps the notifications for everything inside it
				// from reading as changes from outside.
				libraryFileMutations.renameDirectory(source, target, execution.getId());

				return new ExplorerMove(true, null);
			}

			// The digest this move already proved, kept instead of discarded.
			return new ExplorerMove(true, libraryFileMutations.move(source, target, false, execution.getId()));
		} catch (IOException exception) {
			log.error("Explorer could not rename {} to {}", source, target, exception);

			executionProgressService.fail(ownership, ExplorerMessages.renameFailed(exception.getMessage()));

			return new ExplorerMove(false, null);
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
	private void repointCatalog(Execution execution, Path source, Path target, boolean directory,
			MoveBaseline moved, List<PreparedMovement> reserved) {
		// The instant the operation happened, and the same one whichever shape it
		// takes. A retry that finds its own movements already prepared re-applies the
		// same facts under their own identities, so the door recognises them rather
		// than writing a second history dated at the retry.
		CatalogFactProvenance provenance = new CatalogFactProvenance(Instant.now(clock),
				CatalogEventSources.EXPLORER, CatalogEventEvidence.NIMBUS_OPERATION, null);

		if (!directory) {
			// Nothing catalogued sat there, so there is no fact to record - the file the
			// user renamed is one the catalog never knew.
			if (reserved.isEmpty()) {
				return;
			}

			explorerRenamePersistence.rename(source, target, reserved.getFirst().catalogFileEventPublicId(), moved);

			return;
		}

		int repointed = catalogMutations.repointFolder(PathUtils.normalize(source), PathUtils.normalize(target),
				reserved.stream().map(PreparedMovement::catalogFileId).toList(),
				reserved.stream().map(PreparedMovement::catalogFileEventPublicId).toList(), provenance);

		log.info("Execution {} renamed a folder and moved {} catalogued file(s) with it", execution.getId(),
				repointed);

		if (repointed > 0 && eligibilityAnnouncer.repointCanChangeEligibility(PathUtils.normalize(source),
				PathUtils.normalize(target))) {
			eligibilityAnnouncer.announce("explorer folder rename");
		}
	}
}