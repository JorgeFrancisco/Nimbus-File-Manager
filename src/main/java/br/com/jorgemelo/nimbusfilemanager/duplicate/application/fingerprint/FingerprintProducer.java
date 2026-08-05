package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import java.time.LocalDateTime;
import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintFailureReason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.FingerprintFailureDetail;

/**
 * The media-specific half of a fingerprint backlog, plugged into the neutral
 * {@link FingerprintBacklogEngine}. It owns everything that differs by media
 * type - the derived pending query, the exhausted-failure query (which filters
 * by {@code file_type}), the heavy per-item computation and how the result is
 * stored (a photo writes one row, a video several). The engine owns everything
 * that does not - the batch drain, transactions, failure bookkeeping, retry
 * bounds, inventory yielding and rebuild/reset.
 *
 * <p>
 * Adding a new algorithm is a new implementation of this contract; the engine
 * never changes and there is no {@code PHOTO}/{@code VIDEO} switch anywhere.
 *
 * @param <P> the pending work item (carries the catalog file id and its path)
 * @param <R> the computed fingerprint the item produces
 */
interface FingerprintProducer<P, R> {

	FingerprintKind kind();

	String algorithm();

	/** Maximum attempts before an item is considered a terminal failure. */
	int maxAttempts();

	/** Next batch of items still needing a fingerprint (already de-duplicated). */
	List<P> fetchPendingBatch(int batchSize);

	/**
	 * Writes one task for every file this kind could fingerprint, leaving the ones
	 * already owed alone. Media-specific because what counts as a candidate is: a
	 * photo needs a place on disk, a video needs a duration to sample by.
	 *
	 * @return how many were added by this call
	 */
	long seedRebuildTasks(LocalDateTime seededAt);

	/** How many items still need a fingerprint. */
	long countPending();

	/** How many items exhausted their attempts (excluding terminal unsupported). */
	long countExhaustedFailures();

	/** Exhausted failures with their current path, for the details modal. */
	List<FingerprintFailureDetail> exhaustedFailures();

	long catalogFileId(P pending);

	/** Heavy computation; runs off-transaction on the processing pool. */
	R compute(P pending);

	/** Persists the fingerprint row(s); runs inside the batch transaction. */
	void store(P pending, R result);

	/**
	 * Forgets what was concluded from this file's previous fingerprint.
	 *
	 * <p>
	 * Media-specific because the scope is the algorithm: a video whose frames were
	 * re-sampled must lose its video relations, and a photo sharing that catalog
	 * row must keep the ones nothing touched. Called in the same transaction that
	 * replaced the fingerprint, because a relation drawn from a hash that has just
	 * been replaced is one nobody can check - and nothing in the published result
	 * can tell the two apart.
	 */
	void forgetWhatWasDerivedFrom(long catalogFileId);

	/**
	 * Drops the debts of files this rebuild can no longer pay, by the same
	 * definition of candidate the seed used.
	 *
	 * @return how many debts were dropped
	 */
	int discardIneligibleRebuildTasks();

	/**
	 * Why this item has no fingerprint. The producer knows where its file is, so it
	 * is the one that can read the bytes and tell a blank file from a format the
	 * decoder never supported.
	 */
	FingerprintFailureReason reason(P pending, Throwable error);
}