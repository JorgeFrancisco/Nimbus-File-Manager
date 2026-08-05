package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateDeletionPersistence;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.IntakeOutcome;
import br.com.jorgemelo.nimbusfilemanager.settings.application.QuarantineFolderPolicy;
import br.com.jorgemelo.nimbusfilemanager.shared.application.library.LibraryFileMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
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

	private final DuplicateDeletionPersistence quarantinePersistence;
	private final LibraryFileMutations libraryFileMutations;
	private final QuarantineFolderPolicy quarantineFolderPolicy;

	public QuarantineIntakeService(DuplicateDeletionPersistence quarantinePersistence,
			LibraryFileMutations libraryFileMutations,
			QuarantineFolderPolicy quarantineFolderPolicy) {
		this.quarantinePersistence = quarantinePersistence;
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
	public IntakeOutcome intake(Execution execution, CatalogFile file, Path quarantineRoot, MovementReason reason,
			Long executionId) {
		if (!file.isActive()) {
			log.warn("Quarantine intake skipped media file {} because its lifecycle is {}", file.getId(),
					file.getLifecycleStatus());

			return IntakeOutcome.SKIPPED;
		}

		Path source = PathUtils.normalizePath(file.getFileKey());

		if (source.startsWith(quarantineRoot)) {
			log.warn("Quarantine intake skipped media file {} because it is already under quarantine: {}", file.getId(),
					source);

			return IntakeOutcome.SKIPPED;
		}

		if (!PhysicalFilePolicy.isProcessable(source)) {
			log.warn("Quarantine intake skipped a non-physical entry: {}", source);

			return IntakeOutcome.SKIPPED;
		}

		if (!Files.exists(source)) {
			log.warn("Quarantine intake skipped a missing file: {}", source);

			return IntakeOutcome.SKIPPED;
		}

		Path target = target(quarantineRoot, execution, file);

		return move(execution, file, source, target, reason, executionId);
	}

	private IntakeOutcome move(Execution execution, CatalogFile file, Path source, Path target,
			MovementReason reason, Long executionId) {
		try {
			// Same secure move as organization: SHA-256 baseline + byte-for-byte verify.
			libraryFileMutations.move(source, target, false, executionId);
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

			return IntakeOutcome.ERROR;
		}

		return persist(execution, file, source, target, reason);
	}

	private IntakeOutcome persist(Execution execution, CatalogFile file, Path source, Path target,
			MovementReason reason) {
		try {
			quarantinePersistence.persistQuarantine(execution, file, source, target, reason);
		} catch (Exception e) {
			boolean rolledBack = libraryFileMutations.rollback(target, source);

			if (rolledBack) {
				log.error("Quarantine intake moved {} but failed to update the catalog; rolled back", source, e);
			} else {
				log.error("Quarantine intake moved {} to {} but failed to update the catalog AND could not roll back; "
						+ "the file is orphaned in quarantine and needs manual recovery", source, target, e);
			}

			return IntakeOutcome.ERROR;
		}

		return IntakeOutcome.MOVED;
	}

	/**
	 * Collision-safe placement: quarantined files frequently share a name (both
	 * duplicates and the original of a conversion), so the quarantine copy is
	 * namespaced by execution and media-file id ({@code exec-<id>/<id>__<name>}).
	 * The {@code Movement} row keeps the exact original and quarantine paths, so a
	 * restore is a plain move back regardless of this layout.
	 */
	private Path target(Path quarantineRoot, Execution execution, CatalogFile file) {
		return quarantineRoot.resolve("exec-" + execution.getId()).resolve(file.getId() + "__" + file.getFileName());
	}
}