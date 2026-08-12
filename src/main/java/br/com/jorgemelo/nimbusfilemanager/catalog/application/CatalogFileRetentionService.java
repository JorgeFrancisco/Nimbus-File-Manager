package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalInt;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.shared.application.catalog.CatalogCollectionMutations;
import lombok.extern.slf4j.Slf4j;

/**
 * Retention cleanup of the catalog: permanently removes {@code catalog_file}
 * rows that have been MISSING (their file absent from disk) longer than the
 * configured number of days, anchored on {@code lifecycle_changed_at}.
 * Reconcile marks records MISSING but never removes them, so without this they
 * accumulate forever.
 *
 * <p>
 * Only MISSING is purged. DELETED rows are left untouched on purpose: their
 * removal is owned by the quarantine retention purge, which also clears the
 * quarantined file and its movement audit - purging them here would orphan that
 * flow. This class is pure logic; the schedule lives in
 * {@code CatalogFilePurgeScheduler}.
 */
@Slf4j
@Service
class CatalogFileRetentionService {

	private final CatalogCollectionMutations catalogMutations;
	private final Clock clock;

	CatalogFileRetentionService(CatalogCollectionMutations catalogMutations, Clock clock) {
		this.catalogMutations = catalogMutations;
		this.clock = clock;
	}

	/**
	 * Removes catalog rows MISSING for more than {@code days} days. Their
	 * placement, metadata and media rows cascade away in the database; movement
	 * audit rows are detached (SET NULL), so history is preserved. A non-positive
	 * {@code days} is a no-op (retention disabled).
	 *
	 * <p>
	 * The taking is pinned first, in this same transaction, and the delete happens
	 * only if that held. What is removed here cannot be got back - years of
	 * extracted metadata, perceptual hashes, resolved locations - so a run that
	 * lost its turn while the rows were being chosen must not be the one that
	 * carries the choice out. One statement, one unit: there is nothing to fence
	 * per row.
	 *
	 * @param ownership the taking this purge runs as, held in force for the delete
	 * @return how many catalog rows were removed, or empty when the taking is over
	 * and nothing was touched
	 */
	@Transactional
	OptionalInt purgeMissingOlderThan(int days, ExecutionOwnership ownership) {
		if (days <= 0) {
			return OptionalInt.of(0);
		}

		if (!ownership.pin()) {
			log.info("Catalog missing purge was not carried out: the execution it runs as is no longer the current"
					+ " taking of its row");

			return OptionalInt.empty();
		}

		Instant cutoff = Instant.now(clock).minus(Duration.ofDays(days));

		int purged = catalogMutations.purgeMissingBefore(cutoff);

		log.info("Catalog missing purge finished. removed={}, cutoff={}", purged, cutoff);

		return OptionalInt.of(purged);
	}
}