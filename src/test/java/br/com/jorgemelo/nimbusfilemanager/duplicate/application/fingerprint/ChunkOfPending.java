package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintFailureReason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.FingerprintFailureDetail;

/**
 * One chunk of pending work, with no photo in it.
 *
 * <p>
 * A producer the engine can drive whose compute is a decision rather than a
 * decode: a named file throws and the rest hash. That is what puts both halves
 * of the persistence - the fingerprint stored, the failure recorded - inside a
 * single chunk, which is the unit the taking is checked against.
 *
 * <p>
 * It also carries the hook the fencing tests turn on.
 * {@code afterTheLastCompute} runs when the last of the pending items finishes
 * computing, whichever thread that turns out to be, so a test can arrange for
 * the row to change hands exactly in the gap the chunk boundary exists for:
 * after the hashing, before the write. The counter is what makes it exact -
 * every other item has passed its cancellation check and produced an outcome by
 * then, so nothing is left to a timer.
 */
class ChunkOfPending implements FingerprintProducer<Long, byte[]> {

	private static final int HASH_BYTES = 32;
	private static final int SAMPLE_BYTES = 1024;

	private final List<Long> pending;
	private final long failsToCompute;
	private final MediaFingerprintRepository mediaFingerprintRepository;
	private final Runnable afterTheLastCompute;
	private final AtomicInteger computed = new AtomicInteger();

	private boolean handedOut;

	ChunkOfPending(List<Long> pending, long failsToCompute, MediaFingerprintRepository mediaFingerprintRepository,
			Runnable afterTheLastCompute) {
		this.pending = List.copyOf(pending);
		this.failsToCompute = failsToCompute;
		this.mediaFingerprintRepository = mediaFingerprintRepository;
		this.afterTheLastCompute = afterTheLastCompute;
	}

	@Override
	public FingerprintKind kind() {
		return PhashBacklogService.KIND;
	}

	@Override
	public String algorithm() {
		return DuplicateConstants.ALGORITHM;
	}

	@Override
	public int maxAttempts() {
		return 3;
	}

	/** Everything at once, and nothing on the pass after it. */
	@Override
	public List<Long> fetchPendingBatch(int batchSize) {
		if (handedOut) {
			return List.of();
		}

		handedOut = true;

		return new ArrayList<>(pending);
	}

	/** Not the subject here: the chunk boundary is, so nothing is ever owed. */
	@Override
	public long seedRebuildTasks(LocalDateTime seededAt) {
		return 0;
	}

	@Override
	public long countPending() {
		return handedOut ? 0 : pending.size();
	}

	@Override
	public long countExhaustedFailures() {
		return 0;
	}

	@Override
	public List<FingerprintFailureDetail> exhaustedFailures() {
		return List.of();
	}

	@Override
	public long catalogFileId(Long pendingItem) {
		return pendingItem;
	}

	@Override
	public byte[] compute(Long pendingItem) {
		try {
			if (pendingItem == failsToCompute) {
				throw new IllegalStateException("this one does not decode");
			}

			return new byte[HASH_BYTES];
		} finally {
			if (computed.incrementAndGet() == pending.size()) {
				afterTheLastCompute.run();
			}
		}
	}

	/** Neither is the subject: this producer owes nothing and derives nothing. */
	@Override
	public void forgetWhatWasDerivedFrom(long catalogFileId) {
		// Nothing was derived from these.
	}

	@Override
	public int discardIneligibleRebuildTasks() {
		return 0;
	}

	@Override
	public void store(Long pendingItem, byte[] result) {
		mediaFingerprintRepository.save(MediaFingerprint.builder().catalogFileId(pendingItem).kind(kind())
				.algorithm(algorithm()).sampleIndex(0).hashBytes(result).sampleBytes(new byte[SAMPLE_BYTES])
				.computedAt(LocalDateTime.now()).build());
	}

	@Override
	public FingerprintFailureReason reason(Long pendingItem, Throwable error) {
		return FingerprintFailureReason.UNKNOWN;
	}
}