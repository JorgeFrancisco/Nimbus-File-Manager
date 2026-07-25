package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import java.util.List;

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

	/** Whether the error is a terminal, non-retryable unsupported condition. */
	boolean unsupported(Throwable error);
}