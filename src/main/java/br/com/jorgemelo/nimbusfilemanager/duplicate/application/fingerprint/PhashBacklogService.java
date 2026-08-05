package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DrainResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintFailureReason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintFailureRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintRebuildTaskRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.FingerprintFailureDetail;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PendingPhoto;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence.SimilarityRelationWriter;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.ExternalToolNotRunnableException;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.PhotoPerceptualHashService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.UnsupportedPhotoFingerprintException;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.PhotoPerceptualFingerprint;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * Photo half of the fingerprint backlog: the {@link FingerprintProducer} that
 * knows how to find pending photos, compute a photo's 256-bit pHash (through
 * {@link PhotoPerceptualHashService}, reusing the ffmpeg
 * {@code ExternalToolGate}) and store its single fingerprint row. All the
 * orchestration - batch drain, transactions, retry/failure bookkeeping,
 * inventory yielding, rebuild/reset - lives in the shared
 * {@link FingerprintBacklogEngine}; this class supplies only the photo-specific
 * behavior and keeps the same public API the screen and the async runner
 * already call.
 */
@Service
public class PhashBacklogService
		implements FingerprintProducer<PendingPhoto, PhotoPerceptualFingerprint>, FingerprintBacklog {

	static final FingerprintKind KIND = FingerprintKind.PHOTO_PHASH;
	static final int MAX_ATTEMPTS = 3;

	private final MediaFingerprintRepository mediaFingerprintRepository;
	private final FingerprintFailureRepository fingerprintFailureRepository;
	private final FingerprintRebuildTaskRepository fingerprintRebuildTaskRepository;
	private final PhotoPerceptualHashService photoPerceptualHashService;
	private final SimilarityRelationWriter similarityRelationWriter;
	private final FingerprintBacklogEngine engine;
	private final Clock clock;

	public PhashBacklogService(FingerprintBacklogEngine engine, MediaFingerprintRepository mediaFingerprintRepository,
			FingerprintFailureRepository fingerprintFailureRepository,
			FingerprintRebuildTaskRepository fingerprintRebuildTaskRepository,
			PhotoPerceptualHashService photoPerceptualHashService,
			SimilarityRelationWriter similarityRelationWriter, Clock clock) {
		this.engine = engine;
		this.mediaFingerprintRepository = mediaFingerprintRepository;
		this.fingerprintFailureRepository = fingerprintFailureRepository;
		this.fingerprintRebuildTaskRepository = fingerprintRebuildTaskRepository;
		this.photoPerceptualHashService = photoPerceptualHashService;
		this.similarityRelationWriter = similarityRelationWriter;
		this.clock = clock;
	}

	/**
	 * True while an inventory execution is active. Read by the Duplicados screen to
	 * show the inventory's own progress, which is why it stays apart from
	 * {@link #pausedByActiveExecution()} - a conversion also pauses the backlog,
	 * but it is not an inventory and the screen must not say it is.
	 */
	public boolean inventoryActive() {
		return engine.inventoryActive();
	}

	/** Whether a conversion is holding the quarantine a deletion would write to. */
	public boolean conversionActive() {
		return engine.conversionActive();
	}

	@Override
	public boolean pausedByActiveExecution() {
		return engine.pausedByActiveExecution();
	}

	public FingerprintBacklogStatus status() {
		return engine.status(this);
	}

	/** Exhausted items displayed on demand by the failure-details modal. */
	public List<FingerprintFailureDetail> failures() {
		return engine.failures(this);
	}

	/** Manual retry: exhausted failures return to the pending queue. */
	public long resetFailures() {
		return engine.resetFailures(this);
	}

	/**
	 * Every catalogued photo that has somewhere to be read from. The placement is
	 * required for the same reason the pending query joins it: a row with no path
	 * is not something a decoder can be pointed at.
	 */
	@Override
	public long seedRebuildTasks(LocalDateTime seededAt) {
		return fingerprintRebuildTaskRepository.seedPhotos(KIND.name(), DuplicateConstants.ALGORITHM, seededAt);
	}

	@Override
	public boolean rebuildIsOpen() {
		return engine.rebuildIsOpen(this);
	}

	/**
	 * Opens a rebuild by writing down what it owes. Nothing derived is discarded
	 * here and nothing is discarded anywhere: each photo's fingerprint is replaced
	 * by its own, later, and until then it is still the answer.
	 */
	@Override
	public OptionalLong seedRebuild(ExecutionOwnership ownership) {
		return engine.seedRebuild(this, ownership);
	}

	@Override
	public DrainResult drainPending(BooleanSupplier stop, ProgressListener progress,
			ExecutionOwnership ownership) {
		return engine.drain(this, stop, progress, ownership);
	}

	@Override
	public FingerprintKind kind() {
		return KIND;
	}

	@Override
	public String algorithm() {
		return DuplicateConstants.ALGORITHM;
	}

	@Override
	public int maxAttempts() {
		return MAX_ATTEMPTS;
	}

	/**
	 * Where the candidates come from, and there are two answers because there are
	 * two questions.
	 *
	 * <p>
	 * A rebuild owes a list, and that list is the authority while it is open: the
	 * files on it all have a fingerprint already - the one about to be replaced -
	 * so asking the ordinary question would answer nothing. Outside a rebuild the
	 * ordinary question is the right one: what has no fingerprint yet. Never both,
	 * and never merged.
	 */
	@Override
	public List<PendingPhoto> fetchPendingBatch(int batchSize) {
		if (rebuildIsOpen()) {
			return deduplicate(fingerprintRebuildTaskRepository.findOwedPhotos(KIND, DuplicateConstants.ALGORITHM,
					MAX_ATTEMPTS, PageRequest.of(0, batchSize)));
		}

		return deduplicate(mediaFingerprintRepository.findPendingPhotos(KIND, DuplicateConstants.ALGORITHM,
				MAX_ATTEMPTS, PageRequest.of(0, batchSize)));
	}

	@Override
	public long countPending() {
		return mediaFingerprintRepository.countPendingPhotos(KIND, DuplicateConstants.ALGORITHM, MAX_ATTEMPTS);
	}

	@Override
	public long countExhaustedFailures() {
		return fingerprintFailureRepository.countExhaustedFailures(KIND, DuplicateConstants.ALGORITHM, MAX_ATTEMPTS);
	}

	@Override
	public List<FingerprintFailureDetail> exhaustedFailures() {
		return fingerprintFailureRepository.findExhaustedWithPath(KIND, DuplicateConstants.ALGORITHM, MAX_ATTEMPTS);
	}

	@Override
	public long catalogFileId(PendingPhoto pending) {
		return pending.catalogFileId();
	}

	@Override
	public PhotoPerceptualFingerprint compute(PendingPhoto photo) {
		return photoPerceptualHashService.compute(Path.of(photo.path()));
	}

	@Override
	public void store(PendingPhoto photo, PhotoPerceptualFingerprint fingerprint) {
		mediaFingerprintRepository.save(MediaFingerprint.builder().catalogFileId(photo.catalogFileId()).kind(KIND)
				.algorithm(DuplicateConstants.ALGORITHM).sampleIndex(0).hashBytes(fingerprint.hash())
				.sampleBytes(fingerprint.luminance()).computedAt(LocalDateTime.now(clock)).build());
	}

	/**
	 * Photo relations only. A still exported beside a clip shares a catalog row
	 * with it and nothing else, so forgetting by file alone would cost the other
	 * medium a recomputation it never asked for.
	 */
	@Override
	public void forgetWhatWasDerivedFrom(long catalogFileId) {
		similarityRelationWriter.forget(DuplicateConstants.ALGORITHM, catalogFileId);
	}

	@Override
	public int discardIneligibleRebuildTasks() {
		return fingerprintRebuildTaskRepository.discardIneligiblePhotos(KIND.name(), DuplicateConstants.ALGORITHM,
				FileType.PHOTO.name());
	}

	@Override
	public FingerprintFailureReason reason(PendingPhoto pending, Throwable error) {
		// Asked before the bytes are read at all: the classifier answers what is wrong
		// with the file, and a decoder that never started has not looked at one.
		if (error instanceof ExternalToolNotRunnableException) {
			return FingerprintFailureReason.TOOL_UNAVAILABLE;
		}

		if (error instanceof UnsupportedPhotoFingerprintException) {
			return FingerprintFailureReason.UNSUPPORTED_FORMAT;
		}

		return FingerprintFailureClassifier.classify(PathUtils.normalizePath(pending.path()));
	}

	private List<PendingPhoto> deduplicate(List<PendingPhoto> rows) {
		Map<Long, PendingPhoto> byId = new LinkedHashMap<>();

		for (PendingPhoto row : rows) {
			byId.putIfAbsent(row.catalogFileId(), row);
		}

		return new ArrayList<>(byId.values());
	}
}