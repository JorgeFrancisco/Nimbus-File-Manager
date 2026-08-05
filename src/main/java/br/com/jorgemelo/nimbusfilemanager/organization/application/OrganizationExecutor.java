package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancelledException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OwnershipLostException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionMessages;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MovePaths;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MovePreparation;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationItem;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPlan;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.MoveResult;
import br.com.jorgemelo.nimbusfilemanager.shared.application.library.LibraryFileMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhase;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStepType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.NumberUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PhysicalFilePolicy;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class OrganizationExecutor {

	private static final String SKIPPED_LABEL = ", skipped=";
	private static final String ERRORS_LABEL = ", errors=";
	private static final int LIVE_PROGRESS_STRIDE = 25;
	private static final int STEP_PROGRESS_STRIDE = 500;

	private final OrganizationPlanner organizationPlanner;
	private final ExecutionRepository executionRepository;
	private final CatalogFileRepository catalogFileRepository;
	private final CatalogFileLocationRepository catalogFileLocationRepository;
	private final OrganizationMovementLog organizationMovementLog;
	private final OperationLockService operationLockService;
	private final ExecutionProgressService executionProgressService;
	private final ExecutionCancellationService executionCancellationService;
	private final LibraryFileMutations libraryFileMutations;
	private final OrganizationMovePersistence organizationMovePersistence;
	private final EmptyDirectoryCleaner emptyDirectoryCleaner;
	private final Clock clock;

	@Autowired
	public OrganizationExecutor(OrganizationPlanner organizationPlanner, ExecutionRepository executionRepository,
			CatalogFileRepository catalogFileRepository, CatalogFileLocationRepository catalogFileLocationRepository,
			OrganizationMovementLog organizationMovementLog, OperationLockService operationLockService,
			ExecutionProgressService executionProgressService,
			ExecutionCancellationService executionCancellationService, LibraryFileMutations libraryFileMutations,
			OrganizationMovePersistence organizationMovePersistence, EmptyDirectoryCleaner emptyDirectoryCleaner,
			Clock clock) {
		this.organizationPlanner = organizationPlanner;
		this.executionRepository = executionRepository;
		this.catalogFileRepository = catalogFileRepository;
		this.catalogFileLocationRepository = catalogFileLocationRepository;
		this.organizationMovementLog = organizationMovementLog;
		this.operationLockService = operationLockService;
		this.executionProgressService = executionProgressService;
		this.executionCancellationService = executionCancellationService;
		this.libraryFileMutations = libraryFileMutations;
		this.organizationMovePersistence = organizationMovePersistence;
		this.emptyDirectoryCleaner = emptyDirectoryCleaner;
		this.clock = clock;
	}

	/**
	 * Runs a dry run, which is what the application's preview is: every read-only
	 * check happens and nothing is written, so there is no ownership to ask about.
	 */
	public OrganizationExecuteResponse execute(OrganizationExecuteRequest request, Execution execution) {
		return execute(request, execution, null, _ -> {
		});
	}

	/**
	 * @param ownership the worker's claim on the source and target trees, checked
	 * before every move. Only a dry run may pass {@code null}: a real move under no
	 * ownership is a process writing to a library it may already have lost the
	 * right to write to, which is the one state this whole mechanism exists to
	 * prevent
	 */
	public OrganizationExecuteResponse execute(OrganizationExecuteRequest request, Execution execution,
			ExecutionOwnership ownership) {
		return execute(request, execution, ownership, _ -> {
		});
	}

	/**
	 * @param onPlanned handed the plan as soon as it exists, before a single file
	 * is touched. It is how the preview gets what it came for without this class
	 * knowing where a plan is kept - and why a real run, which passes nothing,
	 * stops writing a plan that nobody ever read
	 */
	public OrganizationExecuteResponse execute(OrganizationExecuteRequest request, Execution execution,
			ExecutionOwnership ownership, Consumer<OrganizationPlan> onPlanned) {
		boolean dryRun = request.dryRunValue();

		if (!dryRun && ownership == null) {
			throw new IllegalStateException("A real organization has to run under an execution that owns its paths");
		}

		long moved = 0;
		long skipped = 0;
		long errors = 0;

		try (var _ = operationLockService.acquire(ExecutionType.ORGANIZATION, request.source(), request.target())) {
			OrganizationPlan plan = organizationPlanner.preview(request.toPreviewRequest());

			onPlanned.accept(plan);

			long plannedMoves = plan.items().stream().filter(item -> !item.samePath()).count();

			executionProgressService.updateTotal(execution, plan.items().size());

			if (plan.summary().conflicts() > 0 && !request.allowConflictsValue()) {
				String message = "Organization rejected because the plan contains " + plan.summary().conflicts()
						+ " conflict(s). Run preview and fix conflicts, or execute with allowConflicts=true.";

				finishExecution(execution, ExecutionStatus.REJECTED, plan.summary().totalFiles(), 0, 0, 0,
						ExecutionMessages.rejectedConflicts(plan.summary().conflicts()));

				return toResponse(execution, plannedMoves, 0, plannedMoves, 0, true, message);
			}

			// Move the execution into the PROCESSING phase so the shared progress UI
			// (sidebar banner and progress page) computes and shows the estimated time
			// remaining - its ETA only kicks in there, same as the inventory scanner.
			executionProgressService.updatePhase(execution, ExecutionPhase.PROCESSING,
					ExecutionStepType.PROCESSING_STARTED, ExecutionMessages.processingFiles());

			// Preview already has its own start/finish logs in OrganizationPlanner; log the
			// real move
			// phase here (only when not a dry-run) so an execute run is traceable end to
			// end too.
			if (!dryRun) {
				log.info("Starting organization execute. executionId={}, source={}, target={}, plannedMoves={}",
						execution.getId(), request.source(), request.target(), plannedMoves);
			}

			int processed = 0;

			for (OrganizationItem item : plan.items()) {
				ensureNotCancelled(execution);
				ensureStillOwned(ownership);

				processed++;

				MoveResult result = resolveItemResult(execution, item, request);

				switch (result) {
				case MOVED -> moved++;
				case SKIPPED -> skipped++;
				case ERROR -> errors++;
				}

				reportExecuteProgress(execution, processed, moved, skipped, errors, item);
			}

			ExecutionStatus status = statusOf(errors);

			String message = finishedMessage(dryRun, moved, skipped, errors);

			finishExecution(execution, status, plan.summary().totalFiles(), moved, skipped, errors,
					finishedOutcome(dryRun, moved, skipped, errors));

			if (!dryRun) {
				log.info("Organization execute finished. executionId={}, status={}, moved={}, skipped={}, errors={}",
						execution.getId(), status, moved, skipped, errors);
			}

			return toResponse(execution, plannedMoves, moved, skipped, errors, false, message);
		} catch (ExecutionCancelledException _) {
			String message = "Organization cancelled by user. moved=" + moved + SKIPPED_LABEL + skipped + ERRORS_LABEL
					+ errors + ".";

			finishExecution(execution, ExecutionStatus.CANCELLED, moved + skipped + errors, moved, skipped, errors,
					ExecutionMessages.organizationCancelled(moved, skipped, errors));

			return toResponse(execution, 0, moved, skipped, errors, false, message);
		} catch (OwnershipLostException e) {
			// Nothing went wrong with the work and nothing is half-moved: what went is
			// the right to write here, and it went between two files. Everything already
			// moved was moved while the locks were held and verified byte for byte, so
			// the run ends where it stands instead of failing.
			String message = "Organization interrupted: " + e.getMessage() + " moved=" + moved + SKIPPED_LABEL + skipped
					+ ERRORS_LABEL + errors + ".";

			log.warn(message);

			finishExecution(execution, ExecutionStatus.INTERRUPTED, moved + skipped + errors, moved, skipped, errors,
					ExecutionMessages.executionInterrupted());

			return toResponse(execution, 0, moved, skipped, errors, false, message);
		} catch (OperationLockException e) {
			String message = "Organization rejected: " + e.getMessage();

			finishExecution(execution, ExecutionStatus.ERROR, 0, moved, skipped, errors + 1,
					ExecutionMessages.organizationRejected(e.getMessage()));

			return toResponse(execution, 0, moved, skipped, errors + 1, true, message);
		} catch (Exception e) {
			String message = "Organization failed: " + e.getMessage();

			log.error(message, e);

			finishExecution(execution, ExecutionStatus.ERROR, 0, moved, skipped, errors + 1,
					ExecutionMessages.organizationFailed(e.getMessage()));

			return toResponse(execution, 0, moved, skipped, errors + 1, false, message);
		} finally {
			executionCancellationService.forget(execution.getId());
		}
	}

	private void reportExecuteProgress(Execution execution, int processed, long moved, long skipped, long errors,
			OrganizationItem item) {
		boolean step = processed == 1 || processed % STEP_PROGRESS_STRIDE == 0;
		boolean live = processed % LIVE_PROGRESS_STRIDE == 0;

		if (!step && !live) {
			return;
		}

		int movedInt = NumberUtils.toInt(moved);
		int skippedInt = NumberUtils.toInt(skipped);
		int errorsInt = NumberUtils.toInt(errors);

		// A durable step at the coarse cadence keeps the audit trail small; the finer
		// live updates only refresh the row so the bar advances per file without
		// flooding the steps table.
		if (step) {
			executionProgressService.updateProgress(execution, processed, movedInt, skippedInt, errorsInt,
					item == null ? null : item.sourcePath());
		} else {
			executionProgressService.updateLiveProgress(execution, processed, movedInt, skippedInt, errorsInt,
					item == null ? ExecutionMessages.progressUpdated()
							: ExecutionMessages.processing(item.sourcePath()));
		}
	}

	private ExecutionStatus statusOf(long errors) {
		return errors > 0 ? ExecutionStatus.FINISHED_WITH_ERRORS : ExecutionStatus.FINISHED;
	}

	/**
	 * The sentence this call returns to whoever asked for it, in this process.
	 * What the row keeps is the coded outcome beside it, which the request that
	 * reads it localises.
	 */
	private String finishedMessage(boolean dryRun, long moved, long skipped, long errors) {
		return (dryRun ? "Preview finished. would move=" : "Organization finished. moved=") + moved + SKIPPED_LABEL
				+ skipped + ERRORS_LABEL + errors + ".";
	}

	private ExecutionMessage finishedOutcome(boolean dryRun, long moved, long skipped, long errors) {
		return dryRun ? ExecutionMessages.previewFinished(moved, skipped, errors)
				: ExecutionMessages.organizationFinished(moved, skipped, errors);
	}

	/**
	 * Guard clause pulled out of the execute loop: aborts the run with the same
	 * {@link ExecutionCancelledException} the loop used to throw inline, so the
	 * cancellation path (and the partial counters carried into the catch block)
	 * stays identical.
	 */
	private void ensureNotCancelled(Execution execution) {
		if (executionCancellationService.isCancelled(execution.getId())) {
			throw new ExecutionCancelledException("Organization cancelled by user.");
		}
	}

	/**
	 * Asked once per file rather than once per batch, which is a stronger reading
	 * than the design asks for and is affordable here for a measurable reason: each
	 * move already reads the whole file twice, once for the baseline and once to
	 * verify it. A round trip on an idle connection next to that is noise, and what
	 * it buys is that no file is moved on the strength of a lock the previous file
	 * still had.
	 *
	 * <p>
	 * A dry run has no ownership to ask about, because it writes nothing.
	 */
	private void ensureStillOwned(ExecutionOwnership ownership) {
		if (ownership != null) {
			ownership.assertStillOwned();
		}
	}

	/**
	 * Classifies a single plan item for the execute loop. A same-path item is a
	 * no-op skip (it must never reach {@link #moveOne}); everything else is
	 * delegated to {@code moveOne}. The caller's {@code switch} counts the returned
	 * result and reports progress exactly once, matching the original inline flow.
	 */
	private MoveResult resolveItemResult(Execution execution, OrganizationItem item,
			OrganizationExecuteRequest request) {
		if (item.samePath()) {
			return MoveResult.SKIPPED;
		}

		return moveOne(execution, item, request);
	}

	/**
	 * Decides and (unless dry-run) applies the move for a single item. Every
	 * read-only check runs identically in both modes, so the returned
	 * {@link MoveResult} is exactly what a real execute would produce. The three
	 * side effects - the physical {@code Files.move}, the transactional
	 * {@code persistSuccessfulMove}, and every {@code Movement} row - are the only
	 * differences, and in dry-run they are all blocked (see {@link #recordMovement}
	 * and the {@code dryRun} short-circuit below): dry-run = zero mutation.
	 */
	private MoveResult moveOne(Execution execution, OrganizationItem item, OrganizationExecuteRequest request) {
		Path source = PathUtils.normalizePath(item.sourcePath());

		Path target = PathUtils.normalizePath(item.targetPath());

		MovePaths paths = new MovePaths(source, target);

		boolean dryRun = request.dryRunValue();

		MovePreparation preparation = null;

		try {
			MoveResult guardResult = evaluateGuards(execution, item, paths, dryRun, request);

			if (guardResult != null) {
				return guardResult;
			}

			preparation = prepareDatabaseUpdate(item.internalCatalogFileId(), source);

			// Dry-run choke point: every read-only check passed, so this item WOULD move.
			// Stop before any side effect - no hash, no mkdir, no Files.move, no catalog
			// write, no movement row. This is the single, explicit "zero mutation" gate.
			if (dryRun) {
				return MoveResult.MOVED;
			}

			// Shared reliable move: captures a SHA-256 baseline while the source still
			// exists,
			// moves, and verifies the target byte-for-byte (immune to a stale catalog
			// hash).
			libraryFileMutations.move(source, target, request.overwriteExistingValue(), execution.getId());

			organizationMovePersistence.persistSuccessfulMove(execution, preparation.catalogFile(),
					preparation.location(), source, target);

			removeEmptySourceFolders(source, request.source(), execution);

			return MoveResult.MOVED;
		} catch (Exception e) {
			return handleMoveFailure(execution, item, paths, preparation, dryRun, request, e);
		}
	}

	/**
	 * Runs every read-only pre-move check in order, recording the appropriate
	 * movement and returning the {@link MoveResult} for the first check that blocks
	 * the move, or {@code null} when the item is clear to proceed. Extracted from
	 * {@link #moveOne} so the move flow stays flat and the many audit branches live
	 * on their own.
	 */
	private MoveResult evaluateGuards(Execution execution, OrganizationItem item, MovePaths paths, boolean dryRun,
			OrganizationExecuteRequest request) {
		Path source = paths.source();
		Path target = paths.target();

		if (item.duplicateTarget()) {
			organizationMovementLog.recordMovement(execution, item.internalCatalogFileId(), paths,
					MovementStatus.SKIPPED, MovementReason.DUPLICATE_TARGET,
					"Duplicate target inside the organization plan. File was not moved.", dryRun);

			return MoveResult.SKIPPED;
		}

		CatalogFile existing = catalogFileRepository.findByFileKey(PathUtils.normalize(target)).orElse(null);

		// Never move the real target of a link/junction/.lnk - refuse it outright,
		// even for stale records that predate the physical-only policy. Recorded with a
		// null media file on purpose: a refused link needs no catalog lookup
		// (findById),
		// just an audit row.
		if (!PhysicalFilePolicy.isProcessable(source)) {
			organizationMovementLog.recordMovement(execution, (CatalogFile) null, paths, MovementStatus.SKIPPED,
					MovementReason.SOURCE_NOT_PHYSICAL,
					"Source is a symbolic link, junction or .lnk shortcut and is never moved.", dryRun);

			return MoveResult.SKIPPED;
		}

		if (!Files.exists(source)) {
			if (isAlreadyMoved(item, target, existing)) {
				organizationMovementLog.recordMovement(execution, existing, paths, MovementStatus.SKIPPED,
						MovementReason.ALREADY_MOVED,
						"Source file does not exist, but target file is already registered for this media file.",
						dryRun);

				return MoveResult.SKIPPED;
			}

			organizationMovementLog.recordMovement(execution, item.internalCatalogFileId(), paths, MovementStatus.ERROR,
					MovementReason.SOURCE_NOT_FOUND, "Source file does not exist.", dryRun);

			return MoveResult.ERROR;
		}

		if (existing != null && !Objects.equals(existing.getId(), item.internalCatalogFileId())) {
			organizationMovementLog.recordMovement(execution, item.internalCatalogFileId(), paths,
					MovementStatus.SKIPPED, MovementReason.TARGET_EXISTS,
					"Target path is already registered in the database for another media file.", dryRun);

			return MoveResult.SKIPPED;
		}

		if (Files.exists(target) && !request.overwriteExistingValue()) {
			organizationMovementLog.recordMovement(execution, item.internalCatalogFileId(), paths,
					MovementStatus.SKIPPED, MovementReason.OVERWRITE_DISABLED,
					"Target file already exists and overwriteExisting=false.", dryRun);

			return MoveResult.SKIPPED;
		}

		return null;
	}

	/**
	 * Handles a failure from the physical move or catalog update: classifies it,
	 * attempts a physical rollback when a stray file would otherwise be left
	 * behind, logs a partial failure and records the audit movement. Extracted from
	 * {@link #moveOne}.
	 */
	private MoveResult handleMoveFailure(Execution execution, OrganizationItem item, MovePaths paths,
			MovePreparation preparation, boolean dryRun, OrganizationExecuteRequest request, Exception e) {
		Path source = paths.source();
		Path target = paths.target();

		boolean integrityFailure = e instanceof MoveIntegrityException;

		boolean movedOnDisk = !Files.exists(source) && Files.exists(target);

		boolean rolledBack = movedOnDisk && !request.overwriteExistingValue()
				&& libraryFileMutations.rollback(target, source);

		MovementReason reason = resolveFailureReason(integrityFailure, movedOnDisk);

		String message = partialFailureMessage(integrityFailure, movedOnDisk, rolledBack,
				request.overwriteExistingValue(), e);

		if (movedOnDisk) {
			log.error(
					"Organization partial failure. catalogFileId={} source={} target={} integrityFailure={} physicalRollback={}",
					item.internalCatalogFileId(), source, target, integrityFailure, rolledBack, e);
		}

		organizationMovementLog.recordSafely(execution, preparation == null ? null : preparation.catalogFile(),
				item.internalCatalogFileId(), paths, reason, message, dryRun);

		return MoveResult.ERROR;
	}

	/**
	 * After a file left its folder, delete any now-empty source folders up to (but
	 * not including) the organization source root. Best-effort and non-fatal: the
	 * file is already safely moved, so a cleanup hiccup must never turn a
	 * successful move into an error. The removed folders are rebuilt automatically
	 * on undo, since moving the file back re-creates its parent directories.
	 */
	private void removeEmptySourceFolders(Path movedSource, Path sourceRoot, Execution execution) {
		try {
			Path parent = movedSource.getParent();

			if (parent != null && sourceRoot != null) {
				emptyDirectoryCleaner.removeEmptyAncestors(parent, sourceRoot, execution.getId());
			}
		} catch (RuntimeException e) {
			log.warn("Empty-folder cleanup failed after moving {}; the move itself is unaffected", movedSource, e);
		}
	}

	private boolean isAlreadyMoved(OrganizationItem item, Path target, CatalogFile existing) {
		return existing != null && Objects.equals(existing.getId(), item.internalCatalogFileId())
				&& Files.exists(target);
	}

	private MovementReason resolveFailureReason(boolean integrityFailure, boolean movedOnDisk) {
		if (integrityFailure) {
			return MovementReason.INTEGRITY_CHECK_FAILED;
		}

		return movedOnDisk ? MovementReason.DATABASE_UPDATE_FAILED : MovementReason.IO_ERROR;
	}

	private String partialFailureMessage(boolean integrityFailure, boolean movedOnDisk, boolean rolledBack,
			boolean overwriteExisting, Exception error) {
		if (!movedOnDisk) {
			return error.getMessage();
		}

		String cause = integrityFailure ? "File was moved on disk, but the post-move integrity check failed"
				: "File was moved on disk, but database update or movement record failed";

		if (rolledBack) {
			return cause + ". Physical rollback succeeded: " + error.getMessage();
		}

		if (overwriteExisting) {
			return cause + ". Physical rollback was skipped because overwriteExisting=true: " + error.getMessage();
		}

		return cause + ". Physical rollback failed: " + error.getMessage();
	}

	private MovePreparation prepareDatabaseUpdate(Long catalogFileId, Path source) {
		CatalogFile catalogFile = catalogFileRepository.findById(catalogFileId)
				.orElseThrow(() -> new IllegalStateException("CatalogFile not found: " + catalogFileId));

		CatalogFileLocation location = catalogFileLocationRepository
				.findByCatalogFileIdAndCurrentPath(catalogFileId, PathUtils.normalize(source)).orElseGet(() -> {
					CatalogFileLocation existing = catalogFile.getLocation();

					if (existing != null && PathUtils.normalizePath(existing.getCurrentPath()).equals(source)) {
						return existing;
					}

					throw new IllegalStateException(
							"CatalogFileLocation not found for catalogFileId=" + catalogFileId + " and path=" + source);
				});

		return new MovePreparation(catalogFile, location);
	}

	private void finishExecution(Execution execution, ExecutionStatus status, long filesFound, long filesMoved,
			long skipped, long errors, ExecutionMessage message) {
		// Re-load the managed row so we only touch the finish fields. Saving the
		// caller's
		// in-memory instance instead would clobber columns set in other transactions
		// (notably totalExpected from updateTotal), which showed up as "Total estimado
		// -"
		// and a stuck "Preparando..." in the sidebar after a finished run.
		Execution managed = executionRepository.findById(execution.getId()).orElse(execution);

		managed.setStatus(status);
		managed.setFinishedAt(LocalDateTime.now(clock));
		managed.setFilesFound(NumberUtils.toInt(filesFound));
		managed.setFilesAnalyzed(NumberUtils.toInt(filesFound));
		managed.setFilesMoved(NumberUtils.toInt(filesMoved));
		// cacheHits carries the skipped count (the "Ignorados"/"Já organizados" card),
		// set
		// here so the final value is exact rather than the last progress-cadence
		// sample.
		managed.setCacheHits(NumberUtils.toInt(skipped));
		managed.setErrors(NumberUtils.toInt(errors));

		executionProgressService.applyMessage(managed, message);

		executionRepository.save(managed);

		// Keep the caller's instance in sync so toResponse() reports the final state.
		execution.setStatus(status);
		execution.setFinishedAt(managed.getFinishedAt());
	}

	private OrganizationExecuteResponse toResponse(Execution execution, long plannedMoves, long moved, long skipped,
			long errors, boolean rejected, String message) {
		return new OrganizationExecuteResponse(UuidV7.orLegacy(execution.getPublicId(), execution.getId()),
				execution.getStatus().name(), execution.getStartedAt(), execution.getFinishedAt(),
				execution.getSourcePath(), execution.getTargetPath(), plannedMoves, moved, skipped, errors, rejected,
				message);
	}
}