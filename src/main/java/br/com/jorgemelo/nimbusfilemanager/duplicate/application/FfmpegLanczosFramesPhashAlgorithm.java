package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.FingerprintAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoFrameHash;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoSignature;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.PerceptualHashCodec;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.VideoPerceptualHashService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoPerceptualFingerprint;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.VideoSimilarityProperties;

/**
 * Default video similarity algorithm: frames sampled at deterministic relative
 * positions, each a 256-bit DCT pHash + luminance sample, compared frame-for-
 * frame at the same {@code sampleIndex}. A cheap per-frame pHash distance
 * pre-filters before the expensive SSIM, and the per-frame scores are combined
 * with a <b>trimmed mean plus a minimum concordant-frame quorum</b> so a single
 * divergent frame (a differing intro, an overlay) never sinks - nor fakes - a
 * match. Candidates are bucketed by approximate duration so grouping never does
 * an all-pairs O(n^2) comparison.
 *
 * <p>
 * Robust to re-encoding, bitrate/resolution changes, small duration differences
 * and small compression differences. NOT robust (by design, this version) to
 * start/end trims, inserted segments, speed changes, mirroring, rotation or
 * overlaid elements - those need a future temporal-alignment algorithm, which
 * plugs in as a new {@link VideoSimilarityAlgorithm} without touching this one.
 */
@Component
public class FfmpegLanczosFramesPhashAlgorithm implements VideoSimilarityAlgorithm {

	/**
	 * Number of frames sampled per video. Part of the algorithm's identity (the
	 * {@code ..._FRAMES_V1} string implies 5), NOT a runtime setting: changing it
	 * changes the stored fingerprint's {@code sampleIndex} layout, so it would have
	 * to be a new algorithm string with a rebuild, never a silent config change.
	 */
	static final int FRAME_SAMPLES = 5;

	private static final long NO_DURATION_BUCKET = Long.MIN_VALUE;
	private static final double MIN_DURATION_BUCKET_WIDTH = 0.001;

	private final VideoPerceptualHashService videoPerceptualHashService;
	private final LuminanceSsimService luminanceSsimService;
	private final VideoSimilarityProperties properties;

	public FfmpegLanczosFramesPhashAlgorithm(VideoPerceptualHashService videoPerceptualHashService,
			LuminanceSsimService luminanceSsimService, VideoSimilarityProperties properties) {
		this.videoPerceptualHashService = videoPerceptualHashService;
		this.luminanceSsimService = luminanceSsimService;
		this.properties = properties;
	}

	@Override
	public FingerprintKind kind() {
		return FingerprintKind.VIDEO_PHASH;
	}

	@Override
	public String algorithm() {
		return FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_FRAMES_V1;
	}

	@Override
	public int framesPerFingerprint() {
		return FRAME_SAMPLES;
	}

	@Override
	public VideoPerceptualFingerprint fingerprint(Path file, Double durationSeconds) {
		return videoPerceptualHashService.compute(file, durationSeconds, FRAME_SAMPLES);
	}

	@Override
	public Set<Long> candidateBuckets(VideoSignature signature) {
		if (signature.durationSeconds() == null || !Double.isFinite(signature.durationSeconds())) {
			return Set.of(NO_DURATION_BUCKET);
		}

		double width = Math.max(properties.durationToleranceSecondsOrDefault(), MIN_DURATION_BUCKET_WIDTH);

		long bucket = (long) Math.floor(signature.durationSeconds() / width);

		return Set.of(bucket - 1, bucket, bucket + 1);
	}

	@Override
	public boolean gatesAllow(VideoSignature first, VideoSignature second) {
		return durationCompatible(first, second) && aspectRatioCompatible(first, second);
	}

	@Override
	public int similarityPercent(VideoSignature first, VideoSignature second, int minSimilarityPercent) {
		if (!gatesAllow(first, second)) {
			return -1;
		}

		List<Integer> scores = alignedFrameScores(first, second);

		// The quorum cannot ask for more frames than the videos have. A one-frame
		// video - a phone clip beside a photo - would otherwise be compared against
		// nothing and never match, which is the same as having no fingerprint at all.
		int minConcordant = Math.min(properties.minConcordantFramesOrDefault(),
				Math.min(first.frames().size(), second.frames().size()));

		if (scores.size() < minConcordant) {
			return -1;
		}

		long concordant = scores.stream().filter(score -> score >= minSimilarityPercent).count();

		if (concordant < minConcordant) {
			return -1;
		}

		return trimmedMean(scores);
	}

	/**
	 * Per-frame score for every {@code sampleIndex} present in both videos: 0 when
	 * the cheap pHash distance already rules the frame out, otherwise the SSIM of
	 * the two luminance samples.
	 */
	private List<Integer> alignedFrameScores(VideoSignature first, VideoSignature second) {
		Map<Integer, VideoFrameHash> secondByIndex = new HashMap<>();

		for (VideoFrameHash frame : second.frames()) {
			secondByIndex.put(frame.sampleIndex(), frame);
		}

		int maxDistance = properties.maxFrameHashDistanceOrDefault();

		List<Integer> scores = new ArrayList<>();

		for (VideoFrameHash frame : first.frames()) {
			VideoFrameHash other = secondByIndex.get(frame.sampleIndex());

			if (other == null) {
				continue;
			}

			if (PerceptualHashCodec.distance(frame.phash(), other.phash()) > maxDistance) {
				scores.add(0);
			} else {
				scores.add(luminanceSsimService.similarityPercent(frame.luminance(), other.luminance()));
			}
		}

		return scores;
	}

	private boolean durationCompatible(VideoSignature first, VideoSignature second) {
		Double firstDuration = first.durationSeconds();
		Double secondDuration = second.durationSeconds();

		if (firstDuration == null || secondDuration == null) {
			return true;
		}

		return Math.abs(firstDuration - secondDuration) <= properties.durationToleranceSecondsOrDefault();
	}

	private boolean aspectRatioCompatible(VideoSignature first, VideoSignature second) {
		Double firstRatio = aspectRatio(first);
		Double secondRatio = aspectRatio(second);

		if (firstRatio == null || secondRatio == null) {
			return true;
		}

		double relativeDifference = Math.abs(firstRatio - secondRatio) / Math.max(firstRatio, secondRatio);

		return relativeDifference <= properties.aspectRatioToleranceOrDefault();
	}

	private Double aspectRatio(VideoSignature signature) {
		Integer width = signature.width();
		Integer height = signature.height();

		if (width == null || height == null || width <= 0 || height <= 0) {
			return null;
		}

		return width.doubleValue() / height;
	}

	/**
	 * Mean of the per-frame scores after dropping the configured number of lowest
	 * scores, so a few divergent frames do not drag a strong match down. Never
	 * trims away every frame.
	 */
	private int trimmedMean(List<Integer> scores) {
		List<Integer> sorted = new ArrayList<>(scores);

		sorted.sort(null);

		int trim = Math.min(properties.trimmedLowestFramesOrDefault(), sorted.size() - 1);

		List<Integer> kept = sorted.subList(trim, sorted.size());

		double sum = 0;

		for (int score : kept) {
			sum += score;
		}

		return (int) Math.round(sum / kept.size());
	}
}