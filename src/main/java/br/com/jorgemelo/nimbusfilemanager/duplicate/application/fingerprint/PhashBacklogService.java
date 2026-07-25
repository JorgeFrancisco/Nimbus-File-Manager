package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DrainResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintFailureRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.FingerprintFailureDetail;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PendingPhoto;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.PhotoPerceptualHashService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.UnsupportedPhotoFingerprintException;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.PhotoPerceptualFingerprint;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ProcessingCoordinator;

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
	static final String UNSUPPORTED_PREFIX = FingerprintBacklogEngine.UNSUPPORTED_PREFIX;

	private final MediaFingerprintRepository mediaFingerprintRepository;
	private final FingerprintFailureRepository fingerprintFailureRepository;
	private final PhotoPerceptualHashService photoPerceptualHashService;
	private final FingerprintBacklogEngine engine;
	private final Clock clock;

	public PhashBacklogService(MediaFingerprintRepository mediaFingerprintRepository,
			FingerprintFailureRepository fingerprintFailureRepository,
			PhotoPerceptualHashService photoPerceptualHashService, ProcessingCoordinator processingCoordinator,
			ExecutionQueryService executionQueryService, PlatformTransactionManager transactionManager, Clock clock) {
		this.mediaFingerprintRepository = mediaFingerprintRepository;
		this.fingerprintFailureRepository = fingerprintFailureRepository;
		this.photoPerceptualHashService = photoPerceptualHashService;
		this.engine = new FingerprintBacklogEngine(mediaFingerprintRepository, fingerprintFailureRepository,
				processingCoordinator, executionQueryService, transactionManager, clock);
		this.clock = clock;
	}

	/** True while an inventory execution is active - the backlog yields to it. */
	public boolean inventoryActive() {
		return engine.inventoryActive();
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
	 * Clears only derived pHash/SSIM data; inventory, metadata and SHA-256 are
	 * untouched.
	 */
	public long rebuild() {
		return engine.rebuild(this);
	}

	@Override
	public DrainResult drainPending(BooleanSupplier stop, ProgressListener progress) {
		return engine.drain(this, stop, progress);
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

	@Override
	public List<PendingPhoto> fetchPendingBatch(int batchSize) {
		return deduplicate(mediaFingerprintRepository.findPendingPhotos(KIND, DuplicateConstants.ALGORITHM,
				MAX_ATTEMPTS, PageRequest.of(0, batchSize)));
	}

	@Override
	public long countPending() {
		return mediaFingerprintRepository.countPendingPhotos(KIND, DuplicateConstants.ALGORITHM, MAX_ATTEMPTS);
	}

	@Override
	public long countExhaustedFailures() {
		return fingerprintFailureRepository.countExhaustedFailures(KIND, DuplicateConstants.ALGORITHM, MAX_ATTEMPTS,
				UNSUPPORTED_PREFIX);
	}

	@Override
	public List<FingerprintFailureDetail> exhaustedFailures() {
		return fingerprintFailureRepository.findExhaustedWithPath(KIND, DuplicateConstants.ALGORITHM, MAX_ATTEMPTS,
				UNSUPPORTED_PREFIX);
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
		if (!mediaFingerprintRepository.existsByCatalogFileIdAndKindAndAlgorithmAndSampleIndex(photo.catalogFileId(),
				KIND, DuplicateConstants.ALGORITHM, 0)) {
			mediaFingerprintRepository.save(MediaFingerprint.builder().catalogFileId(photo.catalogFileId()).kind(KIND)
					.algorithm(DuplicateConstants.ALGORITHM).sampleIndex(0).hashBytes(fingerprint.hash())
					.sampleBytes(fingerprint.luminance()).computedAt(LocalDateTime.now(clock)).build());
		}
	}

	@Override
	public boolean unsupported(Throwable error) {
		return error instanceof UnsupportedPhotoFingerprintException;
	}

	private List<PendingPhoto> deduplicate(List<PendingPhoto> rows) {
		Map<Long, PendingPhoto> byId = new LinkedHashMap<>();

		for (PendingPhoto row : rows) {
			byId.putIfAbsent(row.catalogFileId(), row);
		}

		return new ArrayList<>(byId.values());
	}
}