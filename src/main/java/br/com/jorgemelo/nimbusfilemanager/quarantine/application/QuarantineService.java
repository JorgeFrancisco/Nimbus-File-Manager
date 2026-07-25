package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateDeletionPersistence;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.organization.application.SecureFileMove;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineItemResponse;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreBatchResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreOptions;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestoreResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.ConflictResolution;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.RestoreOutcome;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.FilePreviewSupport;
import br.com.jorgemelo.nimbusfilemanager.shared.util.FileTypeIcon;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PhysicalFilePolicy;
import br.com.jorgemelo.nimbusfilemanager.shared.util.SizeFormatter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.enums.Kind;
import lombok.extern.slf4j.Slf4j;

/**
 * Read and restore side of the duplicate quarantine, backing the Quarentena
 * screen. The listing is driven by the {@code Movement} audit rows that
 * recorded each soft-delete (they hold the exact original and quarantine
 * paths), so every item can be moved straight back with the same
 * {@link SecureFileMove} primitive used everywhere else. Restores validate two
 * things per file: that the origin is still reachable (or the user picked an
 * alternate folder) and that nothing already occupies the destination (or the
 * user chose to rename). Nothing is ever overwritten.
 */
@Slf4j
@Service
public class QuarantineService extends LocalizedComponent {

	/**
	 * Upper bound on the numbered-rename search, to avoid an unbounded loop on a
	 * pathological folder.
	 */
	private static final int MAX_RENAME_ATTEMPTS = 10_000;

	private final MovementRepository movementRepository;
	private final DuplicateDeletionPersistence duplicateDeletionPersistence;
	private final SecureFileMove secureFileMove;
	private final OperationLockService operationLockService;

	public QuarantineService(MovementRepository movementRepository,
			DuplicateDeletionPersistence duplicateDeletionPersistence, SecureFileMove secureFileMove,
			OperationLockService operationLockService) {
		this.movementRepository = movementRepository;
		this.duplicateDeletionPersistence = duplicateDeletionPersistence;
		this.secureFileMove = secureFileMove;
		this.operationLockService = operationLockService;
	}

	/** One page of files currently held in quarantine, newest deletion first. */
	@Transactional(readOnly = true)
	public Page<QuarantineItemResponse> list(Pageable pageable) {
		return movementRepository.findByStatusAndReasonOrderByIdDesc(MovementStatus.MOVED,
				MovementReason.DUPLICATE_QUARANTINED, pageable).map(this::toItem);
	}

	/**
	 * Restores a single quarantined file according to {@code options}. Never
	 * overwrites an existing file. Deliberately not {@code @Transactional}: the
	 * physical move happens here and only the catalog write
	 * ({@link DuplicateDeletionPersistence#applyRestore}) needs a transaction, so a
	 * catalog failure rolls back on its own without poisoning an outer transaction
	 * - the same pattern {@code DuplicateDeletionService} uses for the forward
	 * move.
	 */
	public QuarantineRestoreResult restore(UUID movementId, QuarantineRestoreOptions options) {
		QuarantineRestoreOptions effective = options == null ? QuarantineRestoreOptions.defaults() : options;

		Movement movement = movementRepository.findByPublicId(movementId).orElse(null);

		if (movement == null) {
			return result(movementId, RestoreOutcome.ERROR, message("backend.quarantine.itemNotFound"), null);
		}

		if (movement.getStatus() != MovementStatus.MOVED
				|| movement.getReason() != MovementReason.DUPLICATE_QUARANTINED) {
			return result(movementId, RestoreOutcome.ERROR, message("backend.quarantine.notQuarantined"), null);
		}

		if (effective.conflictResolution() == ConflictResolution.SKIP) {
			return result(movementId, RestoreOutcome.SKIPPED, message("backend.quarantine.restoreSkipped"), null);
		}

		Path quarantine = PathUtils.normalizePath(movement.getTargetPath());
		Path original = PathUtils.normalizePath(movement.getSourcePath());

		if (!Files.exists(quarantine)) {
			return result(movementId, RestoreOutcome.MISSING_IN_QUARANTINE, message("backend.quarantine.fileMissing"),
					null);
		}

		if (!PhysicalFilePolicy.isProcessable(quarantine)) {
			// Same rule as the forward path (DuplicateDeletionService): never follow a
			// symlink/junction/.lnk. If the quarantine copy was swapped for a link, refuse
			// instead of "restoring" the link into the library.
			return result(movementId, RestoreOutcome.ERROR, message("backend.quarantine.notPhysical"), null);
		}

		boolean usingOverride = effective.destinationFolder() != null;

		Path destinationFolder = usingOverride ? PathUtils.normalizePath(effective.destinationFolder().toString())
				: original.getParent();

		if (destinationFolder == null) {
			return result(movementId, RestoreOutcome.ERROR, message("backend.quarantine.invalidOriginalPath"), null);
		}

		if (!usingOverride && !Files.isDirectory(destinationFolder)) {
			return result(movementId, RestoreOutcome.ORIGIN_MISSING, message("backend.quarantine.originMissing"), null);
		}

		Path destination = destinationFolder.resolve(original.getFileName());

		if (Files.exists(destination)) {
			if (effective.conflictResolution() == ConflictResolution.RENAME) {
				destination = nextAvailableName(destination);
			} else {
				return result(movementId, RestoreOutcome.CONFLICT, message("backend.quarantine.destinationConflict"),
						null);
			}
		}

		return moveBack(movement, quarantine, destination);
	}

	/**
	 * Restores the given selection at once, using safe defaults (block on a name
	 * collision, no alternate folder). Items that need a decision come back as
	 * conflicts/origin missing and stay in quarantine for the user to resolve one
	 * by one on the screen.
	 */
	public QuarantineRestoreBatchResult restoreMany(List<UUID> movementIds) {
		return restoreAll(movementIds == null ? List.of() : movementIds);
	}

	private QuarantineRestoreBatchResult restoreAll(List<UUID> movementIds) {
		List<QuarantineRestoreResult> items = new ArrayList<>();

		int restored = 0;
		int skipped = 0;
		int conflicts = 0;
		int originMissing = 0;
		int errors = 0;

		for (UUID movementId : movementIds) {
			QuarantineRestoreResult item = restore(movementId, QuarantineRestoreOptions.defaults());

			items.add(item);

			switch (RestoreOutcome.valueOf(item.outcome())) {
			case RESTORED -> restored++;
			case SKIPPED -> skipped++;
			case CONFLICT -> conflicts++;
			case ORIGIN_MISSING -> originMissing++;
			default -> errors++;
			}
		}

		String message = message("backend.quarantine.batchCompleted", restored, conflicts, originMissing, errors);

		return new QuarantineRestoreBatchResult(errors == 0, movementIds.size(), restored, skipped, conflicts,
				originMissing, errors, message, items);
	}

	private QuarantineRestoreResult moveBack(Movement movement, Path quarantine, Path destination) {
		UUID movementId = movement.getPublicId();

		try (var _ = operationLockService.acquire(ExecutionType.DEDUP_DELETE, quarantine, destination)) {
			QuarantineRestoreResult moveError = restoreSecureMove(movementId, quarantine, destination);

			if (moveError != null) {
				return moveError;
			}

			QuarantineRestoreResult catalogError = restoreCatalog(movement, movementId, quarantine, destination);

			if (catalogError != null) {
				return catalogError;
			}

			return result(movementId, RestoreOutcome.RESTORED, message("backend.quarantine.restored"),
					PathUtils.normalize(destination));
		} catch (OperationLockException _) {
			return result(movementId, RestoreOutcome.LOCKED, message("backend.quarantine.pathLocked"), null);
		}
	}

	private QuarantineRestoreResult restoreSecureMove(UUID movementId, Path quarantine, Path destination) {
		try {
			// Same secure move as everywhere else: SHA-256 baseline + byte-for-byte verify.
			secureFileMove.move(quarantine, destination, false);

			return null;
		} catch (Exception moveError) {
			// A verify failure leaves the file at the destination; put it back so nothing
			// is half-restored. If the roll-back itself fails, the file is orphaned.
			boolean orphaned = !Files.exists(quarantine) && Files.exists(destination)
					&& !secureFileMove.rollback(destination, quarantine);

			if (orphaned) {
				log.error(
						"Quarantine restore could not move {} back to {} and could not roll back; the file is "
								+ "orphaned at the destination and needs manual recovery",
						quarantine, destination, moveError);
			} else {
				log.error("Quarantine restore could not move {} back to {}", quarantine, destination, moveError);
			}

			return result(movementId, RestoreOutcome.ERROR,
					message("backend.quarantine.moveFailed", moveError.getMessage()), null);
		}
	}

	private QuarantineRestoreResult restoreCatalog(Movement movement, UUID movementId, Path quarantine,
			Path destination) {
		try {
			duplicateDeletionPersistence.applyRestore(movement, destination);

			return null;
		} catch (Exception catalogError) {
			boolean rolledBack = secureFileMove.rollback(destination, quarantine);

			if (rolledBack) {
				log.error("Quarantine restore moved {} but failed to update the catalog; rolled back", destination,
						catalogError);
			} else {
				log.error(
						"Quarantine restore moved {} to {} but failed to update the catalog AND could not roll "
								+ "back; the restored file is now outside the catalog and needs manual recovery",
						quarantine, destination, catalogError);
			}

			return result(movementId, RestoreOutcome.ERROR, message("backend.quarantine.catalogFailed"), null);
		}
	}

	/**
	 * {@code foo.jpg} -> {@code foo (1).jpg}, {@code foo (2).jpg}, ... first name
	 * free on disk.
	 */
	private Path nextAvailableName(Path desired) {
		Path folder = desired.getParent();

		String fileName = desired.getFileName().toString();

		int dot = fileName.lastIndexOf('.');

		String base = dot > 0 ? fileName.substring(0, dot) : fileName;

		String extension = dot > 0 ? fileName.substring(dot) : "";

		for (int index = 1; index <= MAX_RENAME_ATTEMPTS; index++) {
			Path candidate = folder.resolve(base + " (" + index + ")" + extension);

			if (!Files.exists(candidate)) {
				return candidate;
			}
		}

		// Extremely unlikely; fall back to a UUID suffix so the restore can still
		// proceed.
		return folder.resolve(base + " (" + UUID.randomUUID() + ")" + extension);
	}

	private QuarantineItemResponse toItem(Movement movement) {
		Path original = PathUtils.normalizePath(movement.getSourcePath());
		Path quarantine = PathUtils.normalizePath(movement.getTargetPath());
		Path originFolder = original.getParent();

		CatalogFile catalogFile = movement.getCatalogFile();

		FileType fileType = catalogFile == null ? null : catalogFile.getFileType();

		String typeName = fileType == null ? "OTHER" : fileType.name();

		Long sizeBytes = sizeOf(catalogFile, quarantine);

		UUID mediaPublicId = catalogFile == null ? null : catalogFile.getPublicId();

		boolean present = Files.exists(quarantine);

		Kind kind = FilePreviewSupport.kind(typeName, extensionOf(original));

		// Served by public id through /api/media (now allows soft-deleted files for
		// logged-in users);
		// the card builds the thumbnail URL from the public id, this is the
		// open-in-lightbox content URL.
		boolean previewable = mediaPublicId != null && kind != Kind.NONE;

		String previewUrl = previewable ? "/api/media/" + mediaPublicId + "/content" : null;

		Path quarantineFolder = quarantine.getParent();

		return new QuarantineItemResponse(movement.getPublicId(), movement.getExecution().getPublicId(), mediaPublicId,
				original.getFileName().toString(), PathUtils.normalize(original),
				originFolder == null ? null : PathUtils.normalize(originFolder), PathUtils.normalize(quarantine),
				quarantineFolder == null ? null : PathUtils.normalize(quarantineFolder), sizeBytes,
				sizeBytes == null ? "—" : SizeFormatter.format(sizeBytes), movement.getMovedAt(), present,
				originFolder != null && Files.isDirectory(originFolder), Files.exists(original), typeName,
				FileTypeIcon.iconClass(typeName), FileTypeIcon.iconLabelKey(typeName), kind == Kind.IMAGE,
				kind == Kind.VIDEO, kind == Kind.PDF, kind == Kind.TEXT, kind == Kind.AUDIO, previewUrl);
	}

	private String extensionOf(Path path) {
		String fileName = path.getFileName() == null ? "" : path.getFileName().toString();

		int dot = fileName.lastIndexOf('.');

		return dot >= 0 && dot < fileName.length() - 1 ? fileName.substring(dot + 1) : "";
	}

	private Long sizeOf(CatalogFile catalogFile, Path quarantine) {
		if (catalogFile != null && catalogFile.getSizeBytes() != null) {
			return catalogFile.getSizeBytes();
		}

		try {
			return Files.exists(quarantine) ? Files.size(quarantine) : null;
		} catch (IOException _) {
			return null;
		}
	}

	private QuarantineRestoreResult result(UUID movementId, RestoreOutcome outcome, String message,
			String restoredPath) {
		return new QuarantineRestoreResult(outcome == RestoreOutcome.RESTORED, outcome.name(), message, movementId,
				restoredPath);
	}
}