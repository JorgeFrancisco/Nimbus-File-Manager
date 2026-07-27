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

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.VideoSimilarityAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DrainResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintFailureRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.FingerprintFailureDetail;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PendingVideo;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.UnsupportedVideoFingerprintException;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoFrameFingerprint;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoPerceptualFingerprint;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ProcessingCoordinator;

/**
 * Video half of the fingerprint backlog: the {@link FingerprintProducer} that
 * finds pending videos, computes each one's multi-frame fingerprint (through
 * the active {@link VideoSimilarityAlgorithm}, which reuses the ffmpeg
 * {@code ExternalToolGate}) and stores one {@code media_fingerprint} row per
 * sampled frame. All the orchestration is the shared
 * {@link FingerprintBacklogEngine}; this class only supplies the video-specific
 * behavior. Its {@code (kind, algorithm)} identity comes from the injected
 * algorithm, so swapping the algorithm never touches this class.
 */
@Service
public class VideoFingerprintBacklogService
		implements FingerprintProducer<PendingVideo, VideoPerceptualFingerprint>, FingerprintBacklog {

	static final int MAX_ATTEMPTS = 3;

	private final MediaFingerprintRepository mediaFingerprintRepository;
	private final FingerprintFailureRepository fingerprintFailureRepository;
	private final VideoSimilarityAlgorithm algorithm;
	private final FingerprintBacklogEngine engine;
	private final Clock clock;

	public VideoFingerprintBacklogService(MediaFingerprintRepository mediaFingerprintRepository,
			FingerprintFailureRepository fingerprintFailureRepository, VideoSimilarityAlgorithm algorithm,
			ProcessingCoordinator processingCoordinator, ExecutionQueryService executionQueryService,
			PlatformTransactionManager transactionManager, Clock clock) {
		this.mediaFingerprintRepository = mediaFingerprintRepository;
		this.fingerprintFailureRepository = fingerprintFailureRepository;
		this.algorithm = algorithm;
		this.engine = new FingerprintBacklogEngine(mediaFingerprintRepository, fingerprintFailureRepository,
				processingCoordinator, executionQueryService, transactionManager, clock);
		this.clock = clock;
	}

	@Override
	public boolean pausedByActiveExecution() {
		return engine.pausedByActiveExecution();
	}

	@Override
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

	/** Clears only this algorithm's derived video fingerprints and failures. */
	@Override
	public long rebuild() {
		return engine.rebuild(this);
	}

	@Override
	public DrainResult drainPending(BooleanSupplier stop, ProgressListener progress) {
		return engine.drain(this, stop, progress);
	}

	@Override
	public FingerprintKind kind() {
		return algorithm.kind();
	}

	@Override
	public String algorithm() {
		return algorithm.algorithm();
	}

	@Override
	public int maxAttempts() {
		return MAX_ATTEMPTS;
	}

	@Override
	public List<PendingVideo> fetchPendingBatch(int batchSize) {
		return deduplicate(mediaFingerprintRepository.findPendingVideos(kind(), algorithm(), MAX_ATTEMPTS,
				PageRequest.of(0, batchSize)));
	}

	@Override
	public long countPending() {
		return mediaFingerprintRepository.countPendingVideos(kind(), algorithm(), MAX_ATTEMPTS);
	}

	@Override
	public long countExhaustedFailures() {
		return fingerprintFailureRepository.countExhaustedVideoFailures(kind(), algorithm(), MAX_ATTEMPTS,
				FingerprintBacklogEngine.UNSUPPORTED_PREFIX);
	}

	@Override
	public List<FingerprintFailureDetail> exhaustedFailures() {
		return fingerprintFailureRepository.findExhaustedVideoWithPath(kind(), algorithm(), MAX_ATTEMPTS,
				FingerprintBacklogEngine.UNSUPPORTED_PREFIX);
	}

	@Override
	public long catalogFileId(PendingVideo pending) {
		return pending.catalogFileId();
	}

	@Override
	public VideoPerceptualFingerprint compute(PendingVideo video) {
		return algorithm.fingerprint(Path.of(video.path()), video.durationSeconds());
	}

	@Override
	public void store(PendingVideo video, VideoPerceptualFingerprint fingerprint) {
		LocalDateTime computedAt = LocalDateTime.now(clock);

		for (VideoFrameFingerprint frame : fingerprint.frames()) {
			if (!mediaFingerprintRepository.existsByCatalogFileIdAndKindAndAlgorithmAndSampleIndex(
					video.catalogFileId(), kind(), algorithm(), frame.sampleIndex())) {
				mediaFingerprintRepository
						.save(MediaFingerprint.builder().catalogFileId(video.catalogFileId()).kind(kind())
								.algorithm(algorithm()).sampleIndex(frame.sampleIndex()).positionMs(frame.positionMs())
								.hashBytes(frame.hash()).sampleBytes(frame.luminance()).computedAt(computedAt).build());
			}
		}
	}

	@Override
	public boolean unsupported(Throwable error) {
		return error instanceof UnsupportedVideoFingerprintException;
	}

	private List<PendingVideo> deduplicate(List<PendingVideo> rows) {
		Map<Long, PendingVideo> byId = new LinkedHashMap<>();

		for (PendingVideo row : rows) {
			byId.putIfAbsent(row.catalogFileId(), row);
		}

		return new ArrayList<>(byId.values());
	}
}