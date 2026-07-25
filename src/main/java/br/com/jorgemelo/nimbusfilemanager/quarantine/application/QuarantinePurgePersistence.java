package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.MovementPurgeResult;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;

/**
 * Catalog side of the quarantine retention purge, split into two independent
 * transactions so the reliable part never depends on the risky part:
 *
 * <ol>
 * <li>{@link #deleteMovement} removes the movement audit row when it is still a
 * quarantined item (the item leaves the quarantine listing for good) and
 * reports both whether it was actually removed and which media file it pointed
 * at, so a no-op caused by a concurrent restore is never counted as a
 * purge.</li>
 * <li>{@link #deleteCatalogFileIfOrphan} is a best-effort cleanup of the
 * catalog row itself, run in its own transaction so an unexpected foreign-key
 * constraint from some other table cannot roll back - and thus endlessly retry
 * - the movement/file removal. If it fails, the media file simply stays as
 * {@code DELETED}, which is harmless.</li>
 * </ol>
 */
@Service
public class QuarantinePurgePersistence {

	private final MovementRepository movementRepository;
	private final CatalogFileRepository catalogFileRepository;

	public QuarantinePurgePersistence(MovementRepository movementRepository,
			CatalogFileRepository catalogFileRepository) {
		this.movementRepository = movementRepository;
		this.catalogFileRepository = catalogFileRepository;
	}

	/**
	 * Deletes the movement row and reports the outcome explicitly. Returns a
	 * {@link MovementPurgeResult#notRemoved()} - never mistaken for a real purge -
	 * when the row is gone or is no longer a quarantined item (e.g. it was restored
	 * in the meantime), so a concurrent restore is never clobbered or miscounted;
	 * otherwise deletes the row and returns {@link MovementPurgeResult#removed}
	 * with the media file id it referenced (or {@code null}).
	 */
	@Transactional
	public MovementPurgeResult deleteMovement(Long movementId) {
		Movement movement = movementRepository.findById(movementId).orElse(null);

		if (movement == null || movement.getStatus() != MovementStatus.MOVED
				|| movement.getReason() != MovementReason.DUPLICATE_QUARANTINED) {
			return MovementPurgeResult.notRemoved();
		}

		CatalogFile catalogFile = movement.getCatalogFile();

		Long catalogFileId = catalogFile == null ? null : catalogFile.getId();

		movementRepository.delete(movement);

		return MovementPurgeResult.removed(catalogFileId);
	}

	/**
	 * Removes the catalog row for {@code catalogFileId} only when it is safe: no
	 * other movement still references it and it is in the {@code DELETED}
	 * lifecycle. Returns whether it was deleted. Any foreign-key failure propagates
	 * to the caller, which treats it as "keep the row".
	 */
	@Transactional
	public boolean deleteCatalogFileIfOrphan(Long catalogFileId) {
		if (catalogFileId == null || movementRepository.countByCatalogFileId(catalogFileId) > 0) {
			return false;
		}

		CatalogFile catalogFile = catalogFileRepository.findById(catalogFileId).orElse(null);

		if (catalogFile == null || !catalogFile.isDeleted()) {
			return false;
		}

		catalogFileRepository.delete(catalogFile);

		return true;
	}
}