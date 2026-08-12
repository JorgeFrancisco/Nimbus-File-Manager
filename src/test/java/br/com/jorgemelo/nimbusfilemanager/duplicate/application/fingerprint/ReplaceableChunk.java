package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintFailureReason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintRebuildTaskRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.FingerprintFailureDetail;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence.SimilarityRelationWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ProcessingMetrics;

/**
 * A producer whose compute is a decision, driving the real writes.
 *
 * <p>
 * Everything that touches the database here is the production collaborator - the
 * fingerprint repository, the relation writer, the work list - so what a test
 * observes afterwards is what a rebuild really leaves behind. Only the hashing
 * is decided rather than performed: a file is told to succeed with a given
 * number of samples, to fail in a way that may be retried, or to fail in a way
 * that spends every attempt at once.
 *
 * <p>
 * It also carries the two sabotage points the rollback proofs need. Both throw
 * <em>after</em> the fingerprint has been replaced and before the transaction
 * commits, which is the window where the unit either holds together or does not.
 */
class ReplaceableChunk implements FingerprintProducer<Long, Integer> {

	private static final int HASH_BYTES = 32;
	private static final int SAMPLE_BYTES = 1024;

	private final MediaFingerprintRepository mediaFingerprintRepository;
	private final SimilarityRelationWriter similarityRelationWriter;
	private final FingerprintRebuildTaskRepository fingerprintRebuildTaskRepository;

	private final Map<Long, Integer> samples = new LinkedHashMap<>();
	private final Map<Long, FingerprintFailureReason> failures = new LinkedHashMap<>();

	private Runnable afterStore;
	private Runnable duringForget;
	private boolean handedOut;

	ReplaceableChunk(MediaFingerprintRepository mediaFingerprintRepository,
			SimilarityRelationWriter similarityRelationWriter,
			FingerprintRebuildTaskRepository fingerprintRebuildTaskRepository) {
		this.mediaFingerprintRepository = mediaFingerprintRepository;
		this.similarityRelationWriter = similarityRelationWriter;
		this.fingerprintRebuildTaskRepository = fingerprintRebuildTaskRepository;
	}

	/** This file hashes, and produces the given number of sampled rows. */
	ReplaceableChunk succeedsWith(long catalogFileId, int sampleCount) {
		samples.put(catalogFileId, sampleCount);

		return this;
	}

	/** This file fails for a reason that leaves attempts on the clock. */
	ReplaceableChunk failsRetryably(long catalogFileId) {
		return failsWith(catalogFileId, FingerprintFailureReason.UNKNOWN);
	}

	/** This file fails for a reason no later attempt would answer differently. */
	ReplaceableChunk failsTerminally(long catalogFileId) {
		return failsWith(catalogFileId, FingerprintFailureReason.CORRUPTED_FILE);
	}

	private ReplaceableChunk failsWith(long catalogFileId, FingerprintFailureReason reason) {
		samples.put(catalogFileId, 0);
		failures.put(catalogFileId, reason);

		return this;
	}

	/** Throws once the new fingerprint is written, before anything else runs. */
	ReplaceableChunk brokenAfterTheFingerprintIsReplaced(Runnable sabotage) {
		this.afterStore = sabotage;

		return this;
	}

	/** Throws while the relations of the replaced file are being forgotten. */
	ReplaceableChunk brokenWhileForgettingWhatWasDerived(Runnable sabotage) {
		this.duringForget = sabotage;

		return this;
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
		return PhashBacklogService.MAX_ATTEMPTS;
	}

	@Override
	public List<Long> fetchPendingBatch(int batchSize) {
		if (handedOut) {
			return List.of();
		}

		handedOut = true;

		return new ArrayList<>(samples.keySet());
	}

	@Override
	public long seedRebuildTasks(LocalDateTime seededAt) {
		return fingerprintRebuildTaskRepository.seedPhotos(kind().name(), algorithm(), seededAt);
	}

	@Override
	public long countPending() {
		return handedOut ? 0 : samples.size();
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

	/**
	 * The accumulator goes unused because nothing here runs an external tool: the
	 * sample count is decided, not decoded, so there is no gate wait and no exec to
	 * record.
	 */
	@Override
	public Integer compute(Long pendingItem, ProcessingMetrics metrics) {
		if (failures.containsKey(pendingItem)) {
			throw new IllegalStateException("this one does not decode");
		}

		return samples.get(pendingItem);
	}

	@Override
	public void store(Long pendingItem, Integer sampleCount) {
		for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
			mediaFingerprintRepository.save(MediaFingerprint.builder().catalogFileId(pendingItem).kind(kind())
					.algorithm(algorithm()).sampleIndex(sampleIndex).hashBytes(new byte[HASH_BYTES])
					.sampleBytes(new byte[SAMPLE_BYTES]).computedAt(LocalDateTime.now()).build());
		}

		if (afterStore != null) {
			afterStore.run();
		}
	}

	@Override
	public void forgetWhatWasDerivedFrom(long catalogFileId) {
		if (duringForget != null) {
			duringForget.run();
		}

		similarityRelationWriter.forget(algorithm(), catalogFileId);
	}

	@Override
	public int discardIneligibleRebuildTasks() {
		return fingerprintRebuildTaskRepository.discardIneligiblePhotos(kind().name(), algorithm(),
				FileType.PHOTO.name());
	}

	@Override
	public FingerprintFailureReason reason(Long pendingItem, Throwable error) {
		return failures.get(pendingItem);
	}
}