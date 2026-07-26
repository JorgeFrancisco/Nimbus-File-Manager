package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.MovementPurgeResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantinePurgeResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.Outcome;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * Retention purge of the quarantine folder: permanently removes files that have
 * sat in quarantine longer than the configured number of days, together with
 * their catalog records. Ordering is chosen for consistency (the point
 * {@code Jorge} raised): the physical file is deleted first, then the database
 * row - so a crash in between leaves a row whose file is already gone, which
 * the next run detects and cleans (reconciliation). A per-item operation lock
 * keeps the purge from racing a concurrent restore of the same file.
 *
 * <p>
 * Each run is capped ({@link #MAX_PER_RUN}); leftovers and any items that
 * errored (a file that could not be deleted) are simply retried on the next
 * daily run. This class is pure logic - the schedule lives in
 * {@code QuarantinePurgeScheduler}.
 */
@Slf4j
@Service
public class QuarantinePurgeService {

	/**
	 * How many overdue items a single run will attempt, to bound memory and IO per
	 * pass.
	 */
	private static final int MAX_PER_RUN = 5_000;

	/** Fail-safe: an unreadable retention window runs no purge at all. */
	private static final int DISABLED_RETENTION = -1;

	private final MovementRepository movementRepository;
	private final QuarantinePurgePersistence purgePersistence;
	private final OperationLockService operationLockService;
	private final AppSettingService appSettingService;
	private final Clock clock;

	public QuarantinePurgeService(MovementRepository movementRepository, QuarantinePurgePersistence purgePersistence,
			OperationLockService operationLockService, AppSettingService appSettingService, Clock clock) {
		this.movementRepository = movementRepository;
		this.purgePersistence = purgePersistence;
		this.operationLockService = operationLockService;
		this.appSettingService = appSettingService;
		this.clock = clock;
	}

	/**
	 * How long a file stays in quarantine before the scheduled purge expunges it,
	 * or {@code 0} when no purge runs at all. The fallback is deliberately
	 * non-positive instead of the product default: a blank or invalid setting must
	 * disable the destructive purge, and must not promise the user a deadline
	 * nobody enforces. Read fresh on every call, so a change in Settings applies
	 * immediately.
	 */
	public int retentionDays() {
		return Math.max(0, appSettingService.intValue(SettingsConstants.TRASH_RETENTION_DAYS, DISABLED_RETENTION));
	}

	/**
	 * Expunges quarantined files whose soft-delete happened more than {@code days}
	 * days ago. A non-positive {@code days} is a no-op (retention disabled).
	 */
	QuarantinePurgeResult purgeOlderThan(int days) {
		if (days <= 0) {
			return new QuarantinePurgeResult(0, 0, 0, 0, 0);
		}

		LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(days);

		List<Movement> overdue = movementRepository
				.findByStatusAndReasonInAndMovedAtBeforeOrderByIdAsc(MovementStatus.MOVED,
						QuarantineConstants.QUARANTINED_REASONS, cutoff, PageRequest.of(0, MAX_PER_RUN))
				.getContent();

		int purged = 0;
		int catalogsFreed = 0;
		int skipped = 0;
		int errors = 0;

		for (Movement movement : overdue) {
			switch (purgeOne(movement)) {
			case PURGED -> purged++;
			case PURGED_WITH_CATALOG -> {
				purged++;
				catalogsFreed++;
			}
			case SKIPPED -> skipped++;
			case ERROR -> errors++;
			}
		}

		log.info("Quarantine purge finished. scanned={}, purged={}, catalogsFreed={}, skipped={}, errors={}",
				overdue.size(), purged, catalogsFreed, skipped, errors);

		return new QuarantinePurgeResult(overdue.size(), purged, catalogsFreed, skipped, errors);
	}

	/**
	 * Permanently deletes the selected quarantined files now (the manual
	 * counterpart to the daily retention purge), regardless of age: physical file
	 * plus catalog records, leaving nothing to restore. Items already gone from
	 * disk are reconciled (record cleaned). Ids that are not/no longer quarantined
	 * are skipped.
	 */
	public QuarantinePurgeResult purgeSelected(List<UUID> movementIds) {
		if (movementIds == null || movementIds.isEmpty()) {
			return new QuarantinePurgeResult(0, 0, 0, 0, 0);
		}

		int purged = 0;
		int catalogsFreed = 0;
		int skipped = 0;
		int errors = 0;

		for (UUID movementId : movementIds) {
			Movement movement = movementRepository.findByPublicId(movementId).orElse(null);

			if (movement == null || movement.getStatus() != MovementStatus.MOVED
					|| !QuarantineConstants.QUARANTINED_REASONS.contains(movement.getReason())) {
				skipped++;
				continue;
			}

			switch (purgeOne(movement)) {
			case PURGED -> purged++;
			case PURGED_WITH_CATALOG -> {
				purged++;
				catalogsFreed++;
			}
			case SKIPPED -> skipped++;
			case ERROR -> errors++;
			}
		}

		log.info("Quarantine manual delete finished. requested={}, purged={}, catalogsFreed={}, skipped={}, errors={}",
				movementIds.size(), purged, catalogsFreed, skipped, errors);

		return new QuarantinePurgeResult(movementIds.size(), purged, catalogsFreed, skipped, errors);
	}

	/**
	 * Removes the records of quarantined items whose file is no longer in the
	 * quarantine folder ("Ausente"). Nothing is deleted from disk (there is nothing
	 * there). Crucially, it re-checks each file on disk right now instead of
	 * trusting the screen: if the quarantine folder/drive was just temporarily
	 * unavailable (which makes every item look absent), the files reappear and
	 * their records are kept, so a transient outage never wipes real entries.
	 * Returns how many were removed.
	 */
	public int cleanupAbsent() {
		List<Movement> quarantined = movementRepository.findByStatusAndReasonInOrderByIdDesc(MovementStatus.MOVED,
				QuarantineConstants.QUARANTINED_REASONS, PageRequest.of(0, MAX_PER_RUN)).getContent();

		int removed = 0;

		for (Movement movement : quarantined) {
			Path quarantine = PathUtils.normalizePath(movement.getTargetPath());

			if (Files.exists(quarantine)) {
				continue;
			}

			try (var _ = operationLockService.acquire(ExecutionType.DEDUP_DELETE, quarantine)) {
				// Re-check under the lock: never clean a record whose file is actually there.
				if (!Files.exists(quarantine)) {
					MovementPurgeResult deletion = purgePersistence.deleteMovement(movement.getId());

					if (deletion.removed()) {
						freeCatalog(deletion.catalogFileId());

						removed++;
					}
				}
			} catch (OperationLockException _) {
				log.info("Quarantine absent cleanup skipped a locked item: {}", quarantine);
			}
		}

		log.info("Quarantine absent cleanup removed {} record(s) whose file was no longer in quarantine", removed);

		return removed;
	}

	private boolean deleteQuarantinedFile(Path quarantine) {
		try {
			Files.delete(quarantine);

			return true;
		} catch (IOException e) {
			// Keep the record so the file is retried next run; never orphan a row whose
			// file is still there.
			log.warn("Quarantine purge could not delete {}; keeping its record for retry", quarantine, e);

			return false;
		}
	}

	private Outcome purgeOne(Movement movement) {
		Path quarantine = PathUtils.normalizePath(movement.getTargetPath());

		try (var _ = operationLockService.acquire(ExecutionType.DEDUP_DELETE, quarantine)) {
			boolean fileWasPresent = Files.exists(quarantine);

			if (fileWasPresent && !deleteQuarantinedFile(quarantine)) {
				return Outcome.ERROR;
			}

			// File is gone (just deleted, or already absent -> reconciliation): safe to
			// clean the catalog.
			MovementPurgeResult deletion = purgePersistence.deleteMovement(movement.getId());

			if (!deletion.removed()) {
				// No-op: the row was restored/removed concurrently between listing and now.
				// Nothing was purged, so it must NOT be counted as PURGED.
				log.info("Quarantine purge skipped {} - it was restored or removed concurrently", quarantine);

				return Outcome.SKIPPED;
			}

			removeEmptyParent(quarantine);

			if (!fileWasPresent) {
				log.info("Quarantine purge reconciled {} - the file was already gone; its record was cleaned",
						quarantine);
			}

			return freeCatalog(deletion.catalogFileId()) ? Outcome.PURGED_WITH_CATALOG : Outcome.PURGED;
		} catch (OperationLockException _) {
			// A restore is touching this path right now; leave it for the next run.
			return Outcome.SKIPPED;
		}
	}

	private boolean freeCatalog(Long catalogFileId) {
		if (catalogFileId == null) {
			return false;
		}

		try {
			return purgePersistence.deleteCatalogFileIfOrphan(catalogFileId);
		} catch (Exception e) {
			// Best-effort: an unexpected foreign key from another table just means we keep
			// the DELETED row.
			log.warn("Quarantine purge kept catalog row for media file {} (constraints prevented removal)",
					catalogFileId,
					e);

			return false;
		}
	}

	/** Removes the now-empty {@code exec-<id>} folder left behind, best-effort. */
	private void removeEmptyParent(Path quarantineFile) {
		Path parent = quarantineFile.getParent();

		if (parent == null || !Files.isDirectory(parent)) {
			return;
		}

		try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
			if (!entries.iterator().hasNext()) {
				Files.delete(parent);
			}
		} catch (IOException e) {
			log.debug("Could not remove quarantine subfolder {}", parent, e);
		}
	}
}