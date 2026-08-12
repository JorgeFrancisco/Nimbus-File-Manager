package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.organization.application.MoveIntegrityException;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MoveBaseline;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.IntakeOutcome;
import br.com.jorgemelo.nimbusfilemanager.settings.application.QuarantineFolderPolicy;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.MovementRequest;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.PreparedMovement;
import br.com.jorgemelo.nimbusfilemanager.shared.application.library.LibraryFileMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence.MovementWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PhysicalFilePolicy;
import lombok.extern.slf4j.Slf4j;

/**
 * The single way a file enters the quarantine folder: secure move to the
 * configured quarantine root, catalog repoint plus soft delete, and the
 * {@code Movement} audit row that later lets the Quarentena screen restore it.
 * Every feature that soft-deletes a file goes through here (duplicate removal
 * and the original left behind by a video conversion), so there is exactly one
 * quarantine, one placement layout and one rollback policy - the caller only
 * chooses the {@link MovementReason} that explains why the file was put there.
 *
 * <p>
 * Deliberately not transactional: the physical move happens here and only the
 * catalog write needs a transaction, so a catalog failure rolls back on its own
 * and this class puts the file back on disk.
 */
@Slf4j
@Service
public class QuarantineIntakeService {

	private final QuarantinePersistence quarantinePersistence;
	private final MovementWriter movementWriter;
	private final LibraryFileMutations libraryFileMutations;
	private final QuarantineFolderPolicy quarantineFolderPolicy;

	public QuarantineIntakeService(QuarantinePersistence quarantinePersistence, MovementWriter movementWriter,
			LibraryFileMutations libraryFileMutations, QuarantineFolderPolicy quarantineFolderPolicy) {
		this.quarantinePersistence = quarantinePersistence;
		this.movementWriter = movementWriter;
		this.libraryFileMutations = libraryFileMutations;
		this.quarantineFolderPolicy = quarantineFolderPolicy;
	}

	/**
	 * The configured quarantine root, or empty when the user has not chosen one -
	 * in which case no feature may soft-delete anything.
	 */
	public Optional<Path> root() {
		return quarantineFolderPolicy.root();
	}

	/**
	 * Moves {@code file} into the quarantine root and records it under
	 * {@code reason}. Returns {@code SKIPPED} when the entry is not eligible
	 * (already removed, already under quarantine, not a physical file or gone from
	 * disk) and {@code ERROR} when the move or the catalog write failed - in both
	 * cases the file is left where it was.
	 *
	 * @param executionId the execution this move belongs to, named so the watcher
	 * goes on recognising it as this product's own work for as long as the run
	 * demonstrably holds its paths
	 */
	public IntakeOutcome intake(Execution execution, CatalogFile file, Path quarantineRoot, PreparedMovement operation,
			Long executionId) {
		if (operation == null || operation.status() != MovementStatus.PENDING) {
			// Either this file was never prepared - which a caller cannot legitimately do
			// - or an earlier attempt already settled it. Neither is work to redo.
			log.warn("Quarantine intake had no pending operation for media file {}", file.getId());

			return IntakeOutcome.SKIPPED;
		}

		if (!file.isActive()) {
			log.warn("Quarantine intake skipped media file {} because its lifecycle is {}", file.getId(),
					file.getLifecycleStatus());

			// Only a file the catalog still counts can be removed from it. A missing one
			// has nothing to move and a removed one is already gone, and quarantining
			// either would rewrite a lifecycle this operation never established.
			return skip(execution, operation, MovementReason.SOURCE_NOT_FOUND);
		}

		Path source = PathUtils.normalizePath(operation.requestedSourcePath());
		Path target = PathUtils.normalizePath(operation.requestedTargetPath());

		if (source.startsWith(quarantineRoot)) {
			log.warn("Quarantine intake skipped media file {} because it is already under quarantine: {}", file.getId(),
					source);

			return skip(execution, operation, MovementReason.ALREADY_MOVED);
		}

		if (!PhysicalFilePolicy.isProcessable(source)) {
			log.warn("Quarantine intake skipped a non-physical entry: {}", source);

			return skip(execution, operation, MovementReason.SOURCE_NOT_PHYSICAL);
		}

		if (!Files.exists(source)) {
			// The one case where a missing source is not a dead end: this operation may
			// have moved it itself and died before the catalog heard about it.
			if (movedByThisOperation(operation, target)) {
				// Resumed after a crash: this attempt never read the file, so it has no
				// digest to offer.
				return finish(execution, file, operation, source, target, null);
			}

			log.warn("Quarantine intake skipped a missing file: {}", source);

			return skip(execution, operation, MovementReason.SOURCE_NOT_FOUND);
		}

		return move(execution, file, source, target, operation, executionId);
	}

	/**
	 * Whether the file at the quarantine destination was put there by this very
	 * operation.
	 *
	 * <p>
	 * The proof is the path and not the contents. A quarantine destination is
	 * {@code <root>/exec-<executionId>/<catalogFileId>__<name>}: the folder names
	 * the execution and the leaf names the catalogued file, so nothing but this
	 * execution acting on this file ever computes it. The operation's own
	 * {@code requested_target_path} was written before the move, and only this
	 * product writes into that folder.
	 *
	 * <p>
	 * That is a stronger claim than organization could make, and worth saying why:
	 * there the destination is a folder of the user's library, where a file of the
	 * same size could arrive by any means, so size was the best evidence available
	 * and the gap was declared. Here the destination is a namespace this product
	 * generates and nobody else has a reason to write to.
	 *
	 * <p>
	 * What remains is somebody placing a file at that exact path by hand, which is
	 * possible and is not defended against.
	 */
	private boolean movedByThisOperation(PreparedMovement operation, Path target) {
		if (!Files.exists(target)) {
			return false;
		}

		log.warn("Resuming a quarantine whose file had already been moved. movement={} target={}",
				operation.movementPublicId(), target);

		return true;
	}

	private IntakeOutcome move(Execution execution, CatalogFile file, Path source, Path target,
			PreparedMovement operation, Long executionId) {
		MoveBaseline baseline;

		try {
			// Same secure move as organization: SHA-256 baseline + byte-for-byte verify.
			// The baseline it produces is kept rather than discarded: it is a proof about
			// the current bytes that has already been paid for.
			baseline = libraryFileMutations.move(source, target, false, executionId);
		} catch (Exception e) {
			// An integrity failure leaves the file at target; put it back so nothing is
			// half-moved. If the move never happened (source still there) there is nothing
			// to undo; if the roll-back itself fails, the file is orphaned and must be
			// flagged.
			boolean orphaned = !Files.exists(source) && Files.exists(target)
					&& !libraryFileMutations.rollback(target, source);

			if (orphaned) {
				log.error("Quarantine intake could not securely move {} to quarantine and could not roll back from "
						+ "{}; the file is orphaned and needs manual recovery", source, target, e);
			} else {
				log.error("Quarantine intake could not securely move {} to quarantine", source, e);
			}

			// Nothing moved, or what moved was put back: the operation ends here.
			return fail(execution, operation, integrityOrIoFailure(e));
		}

		return finish(execution, file, operation, source, target, baseline);
	}

	/**
	 * The catalog side, and what to do when it will not commit.
	 *
	 * <p>
	 * A rolled-back file means nothing happened, so the operation failed and says
	 * so. A file left in quarantine with the catalog still pointing at the library
	 * is the one case that must <em>not</em> be called a failure: the physical work
	 * is done and the next attempt has to be able to find it. Marking it failed
	 * would throw away the only anchor that reconciliation has.
	 */
	private IntakeOutcome finish(Execution execution, CatalogFile file, PreparedMovement operation, Path source,
			Path target, MoveBaseline baseline) {
		try {
			quarantinePersistence.persistQuarantine(execution.getId(), operation, file, source, target, baseline);
		} catch (Exception e) {
			if (libraryFileMutations.rollback(target, source)) {
				log.error("Quarantine intake moved {} but failed to update the catalog; rolled back", source, e);

				return fail(execution, operation, MovementReason.DATABASE_UPDATE_FAILED);
			}

			log.error("Quarantine intake moved {} to {} but failed to update the catalog AND could not roll back; "
					+ "the operation stays pending so a later attempt can finish it", source, target, e);

			return IntakeOutcome.ERROR;
		}

		return IntakeOutcome.MOVED;
	}

	private IntakeOutcome skip(Execution execution, PreparedMovement operation, MovementReason reason) {
		movementWriter.markSkipped(execution.getId(), List.of(operation.movementPublicId()), reason);

		return IntakeOutcome.SKIPPED;
	}

	private IntakeOutcome fail(Execution execution, PreparedMovement operation, MovementReason reason) {
		movementWriter.markFailed(execution.getId(), List.of(operation.movementPublicId()), reason);

		return IntakeOutcome.ERROR;
	}

	private MovementReason integrityOrIoFailure(Exception error) {
		return error instanceof MoveIntegrityException ? MovementReason.INTEGRITY_CHECK_FAILED
				: MovementReason.IO_ERROR;
	}

	/**
	 * Every operation this run is about to attempt, on record before the first file
	 * is touched.
	 *
	 * <p>
	 * One statement for the whole batch, keyed by the file it is for. A run that
	 * dies leaves them pending, and the attempt that takes over prepares again and
	 * is handed back exactly these - identities included.
	 */
	public Map<Long, PreparedMovement> prepare(Execution execution, List<CatalogFile> files, Path quarantineRoot,
			MovementReason reason) {
		if (files.isEmpty()) {
			return Map.of();
		}

		List<MovementRequest> requests = files.stream()
				.map(file -> new MovementRequest(file.getId(),
						PathUtils.normalizePath(file.getLocation().getCurrentPath()),
						target(quarantineRoot, execution, file), reason))
				.toList();

		return movementWriter.prepare(execution.getId(), requests).stream()
				.collect(Collectors.toMap(PreparedMovement::catalogFileId, Function.identity()));
	}

	/**
	 * Collision-safe placement: quarantined files frequently share a name (both
	 * duplicates and the original of a conversion), so the quarantine copy is
	 * namespaced by execution and media-file id ({@code exec-<id>/<id>__<name>}).
	 * The {@code Movement} row keeps the exact original and quarantine paths, so a
	 * restore is a plain move back regardless of this layout.
	 */
	private Path target(Path quarantineRoot, Execution execution, CatalogFile file) {
		return quarantineRoot.resolve("exec-" + execution.getId())
				.resolve(file.getId() + "__" + file.getLocation().fileName());
	}
}