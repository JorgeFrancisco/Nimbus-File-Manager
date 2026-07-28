package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Atomic catalog side of a duplicate quarantine move and its restore (undo).
 * Mirrors {@code OrganizationMovePersistence}: the {@link CatalogFile}
 * placement, its lifecycle flag and the {@link Movement} audit row commit in a
 * single transaction, so the catalog and its trail can never diverge. The
 * physical {@code Files.move} happens in {@code DuplicateDeletionService}
 * before these calls; on a thrown transaction the service rolls the file back
 * on disk.
 */
@Service
public class DuplicateDeletionPersistence {

	private final CatalogFileRepository catalogFileRepository;
	private final CatalogFileLocationRepository catalogFileLocationRepository;
	private final MovementRepository movementRepository;
	private final Clock clock;

	public DuplicateDeletionPersistence(CatalogFileRepository catalogFileRepository,
			CatalogFileLocationRepository catalogFileLocationRepository, MovementRepository movementRepository,
			Clock clock) {
		this.catalogFileRepository = catalogFileRepository;
		this.catalogFileLocationRepository = catalogFileLocationRepository;
		this.movementRepository = movementRepository;
		this.clock = clock;
	}

	/**
	 * Points the catalog at the quarantine copy and marks the file DELETED, in one
	 * transaction. The {@code reason} says which feature soft-deleted the file
	 * (duplicate removal or the original of a video conversion); it is what the
	 * Quarentena screen matches on to list and restore it.
	 */
	@Transactional
	public void persistQuarantine(Execution execution, CatalogFile catalogFile, Path original, Path quarantine,
			MovementReason reason) {
		repoint(catalogFile, quarantine);

		catalogFile.markDeleted();

		catalogFileRepository.save(catalogFile);

		movementRepository.save(Movement.builder().execution(execution).catalogFile(catalogFile)
				.sourcePath(PathUtils.normalize(original)).targetPath(PathUtils.normalize(quarantine))
				.status(MovementStatus.MOVED).reason(reason).build());
	}

	/**
	 * Catalog side of an individual quarantine restore driven by the Quarentena
	 * screen: repoints the file to {@code destination} (its original path, or an
	 * alternate the user picked), re-activates it, and flips the existing
	 * quarantine {@link Movement} to {@code UNDONE} so it leaves the quarantine
	 * listing. All in one transaction; the physical move happens before this call.
	 *
	 * <p>
	 * The quarantine row keeps the reason that put the file there and the reversal
	 * is appended as a movement of its own, belonging to the restore - the same
	 * append-only trail an organization undo writes, so a file quarantined and
	 * restored more than once reads in order instead of being rewritten in place.
	 *
	 * <p>
	 * The passed entities arrive detached and their {@code location} is lazy, so
	 * they are re-loaded managed inside this transaction ({@code findByPublicIdIn}
	 * eagerly fetches the location) before being mutated - the same reason the
	 * forward quarantine path loads files that way.
	 */
	@Transactional
	public void applyRestore(Movement movement, Path destination, Execution restoreExecution) {
		CatalogFile detached = movement.getCatalogFile();

		if (detached == null) {
			throw new IllegalStateException("Movement has no media file: " + movement.getId());
		}

		CatalogFile catalogFile = catalogFileRepository.findByPublicIdIn(List.of(detached.getPublicId())).stream()
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("CatalogFile not found: " + detached.getPublicId()));

		repoint(catalogFile, destination);

		catalogFile.markActive();

		catalogFileRepository.save(catalogFile);

		Movement managed = movementRepository.findById(movement.getId()).orElse(movement);

		managed.setStatus(MovementStatus.UNDONE);

		movementRepository.save(managed);

		movementRepository.save(Movement.builder().execution(restoreExecution).catalogFile(catalogFile)
				.sourcePath(managed.getTargetPath()).targetPath(PathUtils.normalize(destination))
				.status(MovementStatus.MOVED).reason(MovementReason.RESTORED_FROM_QUARANTINE).build());
	}

	private void repoint(CatalogFile catalogFile, Path target) {
		catalogFile.setFileKey(PathUtils.normalize(target));

		catalogFile.setFileName(target.getFileName().toString());

		CatalogFileLocation location = catalogFile.getLocation();

		if (location != null) {
			Path parent = target.getParent();

			location.setCurrentPath(PathUtils.normalize(target));
			location.setCurrentFolder(parent == null ? PathUtils.normalize(target) : PathUtils.normalize(parent));
			location.setUpdatedAt(LocalDateTime.now(clock));

			catalogFileLocationRepository.save(location);
		}
	}
}