package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionStopReason;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OperationLockService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineMessages;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.MovementPurgeResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantinePurgeResult;
import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.Outcome;
import br.com.jorgemelo.nimbusfilemanager.shared.application.library.LibraryFileMutations;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Movement;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.MovementRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
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
 * Each run is capped ({@link QuarantineConstants#MAX_PER_RUN}); leftovers and
 * any items that errored (a file that could not be deleted) are simply retried
 * on the next daily run. This class is pure logic - it runs off the queue, and
 * the schedule lives in {@code QuarantinePurgeScheduler}.
 */
@Slf4j
@Service
class QuarantinePurgeService extends LocalizedComponent {

	private final MovementRepository movementRepository;
	private final QuarantinePurgePersistence purgePersistence;
	private final OperationLockService operationLockService;
	private final ExecutionStopReason executionStopReason;
	private final QuarantineOperationLog purgeLog;
	private final LibraryFileMutations libraryFileMutations;
	private final Clock clock;

	QuarantinePurgeService(MovementRepository movementRepository, QuarantinePurgePersistence purgePersistence,
			OperationLockService operationLockService, ExecutionStopReason executionStopReason,
			QuarantineOperationLog purgeLog, LibraryFileMutations libraryFileMutations, Clock clock) {
		this.movementRepository = movementRepository;
		this.purgePersistence = purgePersistence;
		this.operationLockService = operationLockService;
		this.executionStopReason = executionStopReason;
		this.purgeLog = purgeLog;
		this.libraryFileMutations = libraryFileMutations;
		this.clock = clock;
	}

	/**
	 * Expunges quarantined files whose soft-delete happened more than {@code days}
	 * days ago. A non-positive {@code days} is a no-op (retention disabled).
	 */
	QuarantinePurgeResult purgeOlderThan(int days, Execution execution, ExecutionOwnership ownership) {
		if (days <= 0) {
			return new QuarantinePurgeResult(0, 0, 0, 0, 0, 0);
		}

		LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(days);

		List<Movement> overdue = movementRepository
				.findByStatusAndReasonInAndMovedAtBeforeOrderByIdAsc(MovementStatus.MOVED,
						QuarantineConstants.QUARANTINED_REASONS, cutoff,
						PageRequest.of(0, QuarantineConstants.MAX_PER_RUN))
				.getContent();

		// Nothing overdue is asked before this pass is queued, so getting here means
		// the last item was expunged in between; the row exists either way and says
		// so rather than being left to be read as work still to come.
		if (overdue.isEmpty()) {
			purgeLog.finish(ownership, 0, 0, 0, 0, QuarantineMessages.purgeCompleted(0, 0, 0, 0));

			return new QuarantinePurgeResult(0, 0, 0, 0, 0, 0);
		}

		return purgeAll(overdue, overdue.size(), 0, execution, ownership);
	}

	/**
	 * Permanently deletes the selected quarantined files now (the manual
	 * counterpart to the daily retention purge), regardless of age: physical file
	 * plus catalog records, leaving nothing to restore. Items already gone from
	 * disk are reconciled (record cleaned). Ids that are not/no longer quarantined
	 * are skipped.
	 */
	QuarantinePurgeResult purgeSelected(List<UUID> movementIds, Execution execution,
			ExecutionOwnership ownership) {
		if (movementIds == null || movementIds.isEmpty()) {
			return new QuarantinePurgeResult(0, 0, 0, 0, 0, 0);
		}

		List<Movement> selected = stillQuarantined(movementIds);

		int unresolved = movementIds.size() - selected.size();

		// Every id left quarantine between the listing and the click: nothing will be
		// deleted, and the row has to say why - "0 apagados" on its own reads as a
		// success to whoever asked for the deletion.
		if (selected.isEmpty()) {
			purgeLog.finish(ownership, movementIds.size(), 0, unresolved, 0,
					QuarantineMessages.alreadyLeftQuarantine(unresolved));

			return new QuarantinePurgeResult(movementIds.size(), 0, 0, unresolved, 0, 0);
		}

		return purgeAll(selected, movementIds.size(), unresolved, execution, ownership);
	}

	/** The selected ids still quarantined, in the order they were given. */
	private List<Movement> stillQuarantined(List<UUID> movementIds) {
		List<Movement> selected = new ArrayList<>();

		for (UUID movementId : movementIds) {
			Movement movement = movementRepository.findByPublicId(movementId).orElse(null);

			if (movement != null && movement.getStatus() == MovementStatus.MOVED
					&& QuarantineConstants.QUARANTINED_REASONS.contains(movement.getReason())) {
				selected.add(movement);
			}
		}

		return selected;
	}

	/**
	 * The one purge loop, shared by the daily pass and the manual delete. Deleting
	 * a user's file for good is the most destructive thing the application does, so
	 * it runs as an execution: what was purged, what could not be, and which file
	 * failed all end up on the executions screen instead of only in the log.
	 */
	private QuarantinePurgeResult purgeAll(List<Movement> items, int requested, int unresolved, Execution execution,
			ExecutionOwnership ownership) {
		try {
			return purgeEach(execution, items, requested, unresolved, ownership);
		} catch (RuntimeException purgeError) {
			purgeLog.fail(ownership, purgeError.getMessage());

			throw purgeError;
		}
	}

	private QuarantinePurgeResult purgeEach(Execution execution, List<Movement> items, int requested, int unresolved,
			ExecutionOwnership ownership) {
		int purged = 0;
		int catalogsFreed = 0;
		int skipped = unresolved;
		int busy = 0;
		int errors = 0;

		ExecutionStatus stoppedAs = null;

		for (Movement movement : items) {
			// Deleting a file for good is the most irreversible thing here, so both the
			// right to do it and the wish to are confirmed immediately before each one.
			stoppedAs = executionStopReason.of(execution, ownership);

			if (stoppedAs != null) {
				break;
			}

			switch (purgeOne(execution, movement)) {
			case PURGED -> purged++;
			case PURGED_WITH_CATALOG -> {
				purged++;
				catalogsFreed++;
			}
			case SKIPPED -> skipped++;
			case BUSY -> busy++;
			case ERROR -> errors++;
			}
		}

		log.info("Quarantine purge finished. requested={}, purged={}, catalogsFreed={}, skipped={}, busy={}, errors={}",
				requested, purged, catalogsFreed, skipped, busy, errors);

		// A busy item is not a failure - the path is held by another operation and the
		// next pass takes it - so it is counted with the skips, not with the errors.
		if (stoppedAs == null) {
			purgeLog.finish(ownership, requested, purged, skipped + busy, errors,
					outcome(purged, skipped, busy, errors));
		} else {
			purgeLog.stop(ownership, stoppedAs, requested, purged, skipped + busy, errors,
					stoppedOutcome(stoppedAs, purged, skipped, busy, errors));
		}

		return new QuarantinePurgeResult(requested, purged, catalogsFreed, skipped, busy, errors);
	}

	private ExecutionMessage stoppedOutcome(ExecutionStatus stoppedAs, int purged, int skipped, int busy, int errors) {
		if (stoppedAs == ExecutionStatus.CANCELLED) {
			return QuarantineMessages.purgeCancelled(purged, skipped, busy, errors);
		}

		return QuarantineMessages.purgeInterrupted(purged, skipped, busy, errors);
	}

	/**
	 * What the screen shows when the purge ends. A pass that deleted nothing is
	 * the one the person has to be told about in words - "0 apagados" reads as
	 * success - so it says which of the three reasons stopped it and what to do
	 * about it, instead of the four counters that describe a pass that worked.
	 */
	private ExecutionMessage outcome(int purged, int skipped, int busy, int errors) {
		if (purged == 0 && busy > 0) {
			return QuarantineMessages.heldByAnotherOperation(busy);
		}

		if (purged == 0 && errors > 0) {
			return QuarantineMessages.couldNotDeleteFromDisk(errors);
		}

		if (purged == 0 && skipped > 0) {
			return QuarantineMessages.alreadyLeftQuarantine(skipped);
		}

		return QuarantineMessages.purgeCompleted(purged, skipped, busy, errors);
	}

	/**
	 * Removes the records of quarantined items whose file is no longer in the
	 * quarantine folder ("Ausente"). Nothing is deleted from disk - there is
	 * nothing there.
	 *
	 * <p>
	 * The list arrives from a reading the screen took; what makes the operation
	 * safe is that it is not trusted. Every file is looked at again here, under its
	 * own lock, immediately before its record goes: if the quarantine folder or its
	 * drive was briefly unavailable - which makes every item look absent at once -
	 * the files are there again and the records are kept.
	 *
	 * @return how many records were removed
	 */
	int cleanupAbsent(List<UUID> movementIds, Execution execution, ExecutionOwnership ownership) {
		List<Movement> candidates = stillQuarantined(movementIds == null ? List.of() : movementIds);

		try {
			return cleanupEach(execution, candidates, ownership);
		} catch (RuntimeException cleanupError) {
			purgeLog.fail(ownership, cleanupError.getMessage());

			throw cleanupError;
		}
	}

	private int cleanupEach(Execution execution, List<Movement> absent, ExecutionOwnership ownership) {
		int removed = 0;

		for (Movement movement : absent) {
			if (executionStopReason.of(execution, ownership) != null) {
				break;
			}

			Path quarantine = PathUtils.normalizePath(movement.getTargetPath());

			try (var _ = operationLockService.acquire(ExecutionType.QUARANTINE_PURGE, quarantine)) {
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

		int kept = absent.size() - removed;

		log.info("Quarantine absent cleanup removed {} record(s) whose file was no longer in quarantine", removed);

		// No errors to count: an item held by another operation, or whose file came
		// back under the lock, was kept on purpose - the next pass looks again.
		purgeLog.finish(ownership, absent.size(), removed, kept, 0,
				QuarantineMessages.cleanupCompleted(removed, kept));

		return removed;
	}

	private boolean deleteQuarantinedFile(Execution execution, Path quarantine) {
		try {
			// The quarantine root is a setting, and people do put it inside the library
			// they are watching, so emptying it is a mutation of the user's own files -
			// announced to the watcher by the port, like every other one.
			libraryFileMutations.deleteFile(quarantine, execution.getId());

			return true;
		} catch (IOException e) {
			// Keep the record so the file is retried next run; never orphan a row whose
			// file is still there.
			log.warn("Quarantine purge could not delete {}; keeping its record for retry", quarantine, e);

			purgeLog.recordFailure(execution, quarantine, e);

			return false;
		}
	}

	private Outcome purgeOne(Execution execution, Movement movement) {
		Path quarantine = PathUtils.normalizePath(movement.getTargetPath());

		try (var _ = operationLockService.acquire(ExecutionType.QUARANTINE_PURGE, quarantine)) {
			boolean fileWasPresent = Files.exists(quarantine);

			if (fileWasPresent && !deleteQuarantinedFile(execution, quarantine)) {
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

			removeEmptyParent(execution, quarantine);

			if (!fileWasPresent) {
				log.info("Quarantine purge reconciled {} - the file was already gone; its record was cleaned",
						quarantine);
			}

			return freeCatalog(deletion.catalogFileId()) ? Outcome.PURGED_WITH_CATALOG : Outcome.PURGED;
		} catch (OperationLockException _) {
			// Something else owns this path right now - a restore, or a conversion batch
			// moving originals into the same folder. Counted apart from a plain skip
			// because it is the one outcome the user can act on: wait and try again.
			return Outcome.BUSY;
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
					catalogFileId, e);

			return false;
		}
	}

	/** Removes the now-empty {@code exec-<id>} folder left behind, best-effort. */
	private void removeEmptyParent(Execution execution, Path quarantineFile) {
		Path parent = quarantineFile.getParent();

		if (parent == null || !Files.isDirectory(parent)) {
			return;
		}

		try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
			if (!entries.iterator().hasNext()) {
				libraryFileMutations.deleteEmptyDirectory(parent, execution.getId());
			}
		} catch (IOException e) {
			log.debug("Could not remove quarantine subfolder {}", parent, e);
		}
	}
}