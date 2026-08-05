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

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.VideoSimilarityAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DrainResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintFailureReason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintFailureRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.FingerprintRebuildTaskRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.FingerprintFailureDetail;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PendingVideo;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence.SimilarityRelationWriter;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.ExternalToolNotRunnableException;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.UnsupportedVideoFingerprintException;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoFrameFingerprint;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoPerceptualFingerprint;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

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
	private final FingerprintRebuildTaskRepository fingerprintRebuildTaskRepository;
	private final VideoSimilarityAlgorithm algorithm;
	private final SimilarityRelationWriter similarityRelationWriter;
	private final FingerprintBacklogEngine engine;
	private final Clock clock;

	public VideoFingerprintBacklogService(FingerprintBacklogEngine engine,
			MediaFingerprintRepository mediaFingerprintRepository,
			FingerprintFailureRepository fingerprintFailureRepository,
			FingerprintRebuildTaskRepository fingerprintRebuildTaskRepository, VideoSimilarityAlgorithm algorithm,
			SimilarityRelationWriter similarityRelationWriter, Clock clock) {
		this.engine = engine;
		this.mediaFingerprintRepository = mediaFingerprintRepository;
		this.fingerprintFailureRepository = fingerprintFailureRepository;
		this.fingerprintRebuildTaskRepository = fingerprintRebuildTaskRepository;
		this.algorithm = algorithm;
		this.similarityRelationWriter = similarityRelationWriter;
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

	/**
	 * Every catalogued video that has a path and a measured duration - the
	 * duration is what the frame samples are placed by, so a video the catalog has
	 * not measured cannot be sampled and is not owed.
	 */
	@Override
	public long seedRebuildTasks(LocalDateTime seededAt) {
		return fingerprintRebuildTaskRepository.seedVideos(kind().name(), algorithm(), seededAt);
	}

	@Override
	public boolean rebuildIsOpen() {
		return engine.rebuildIsOpen(this);
	}

	/** Opens a rebuild by writing down what it owes, discarding nothing. */
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

	/** The owed list while a rebuild is open, the ordinary queue otherwise. */
	@Override
	public List<PendingVideo> fetchPendingBatch(int batchSize) {
		if (rebuildIsOpen()) {
			return deduplicate(fingerprintRebuildTaskRepository.findOwedVideos(kind(), algorithm(), MAX_ATTEMPTS,
					PageRequest.of(0, batchSize)));
		}

		return deduplicate(mediaFingerprintRepository.findPendingVideos(kind(), algorithm(), MAX_ATTEMPTS,
				PageRequest.of(0, batchSize)));
	}

	@Override
	public long countPending() {
		return mediaFingerprintRepository.countPendingVideos(kind(), algorithm(), MAX_ATTEMPTS);
	}

	@Override
	public long countExhaustedFailures() {
		return fingerprintFailureRepository.countExhaustedVideoFailures(kind(), algorithm(), MAX_ATTEMPTS);
	}

	@Override
	public List<FingerprintFailureDetail> exhaustedFailures() {
		return fingerprintFailureRepository.findExhaustedVideoWithPath(kind(), algorithm(), MAX_ATTEMPTS);
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
			mediaFingerprintRepository.save(MediaFingerprint.builder().catalogFileId(video.catalogFileId())
					.kind(kind()).algorithm(algorithm()).sampleIndex(frame.sampleIndex())
					.positionMs(frame.positionMs()).hashBytes(frame.hash()).sampleBytes(frame.luminance())
					.computedAt(computedAt).build());
		}
	}

	/** Video relations only, for the same reason the photo half says.
	 */
	@Override
	public void forgetWhatWasDerivedFrom(long catalogFileId) {
		similarityRelationWriter.forget(algorithm(), catalogFileId);
	}

	@Override
	public int discardIneligibleRebuildTasks() {
		return fingerprintRebuildTaskRepository.discardIneligibleVideos(kind().name(), algorithm());
	}

	@Override
	public FingerprintFailureReason reason(PendingVideo pending, Throwable error) {
		// Asked before the bytes are read at all: the classifier answers what is wrong
		// with the file, and a decoder that never started has not looked at one.
		if (error instanceof ExternalToolNotRunnableException) {
			return FingerprintFailureReason.TOOL_UNAVAILABLE;
		}

		if (error instanceof UnsupportedVideoFingerprintException) {
			return FingerprintFailureReason.UNSUPPORTED_FORMAT;
		}

		return FingerprintFailureClassifier.classify(PathUtils.normalizePath(pending.path()));
	}

	private List<PendingVideo> deduplicate(List<PendingVideo> rows) {
		Map<Long, PendingVideo> byId = new LinkedHashMap<>();

		for (PendingVideo row : rows) {
			byId.putIfAbsent(row.catalogFileId(), row);
		}

		return new ArrayList<>(byId.values());
	}
}