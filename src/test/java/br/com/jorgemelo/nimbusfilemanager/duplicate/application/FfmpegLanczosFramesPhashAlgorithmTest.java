package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoFrameHash;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoSignature;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.VideoPerceptualHashService;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.VideoSimilarityProperties;

/**
 * Calibration tests for the default video similarity algorithm: they pin how
 * the configurable comparison thresholds (concordant-frame quorum,
 * trimmed-lowest count, per-frame pHash radius, duration/aspect tolerance) turn
 * per-frame agreement into a match percentage. Frames are made concordant with
 * an identical hash + luminance and divergent with an opposite hash (distance
 * 256), so the pHash pre-filter is what drives each frame to 100 or 0.
 */
class FfmpegLanczosFramesPhashAlgorithmTest {

	private static final byte[] LUMA = filled((byte) 100, 1024);
	private static final byte[] HASH_A = filled((byte) 0x00, 32);
	private static final byte[] HASH_B = filled((byte) 0xFF, 32);

	private FfmpegLanczosFramesPhashAlgorithm algorithm(VideoSimilarityProperties properties) {
		return new FfmpegLanczosFramesPhashAlgorithm(mock(VideoPerceptualHashService.class), new LuminanceSsimService(),
				properties);
	}

	private VideoSimilarityProperties properties(int minConcordant, int trimmedLowest) {
		return new VideoSimilarityProperties(minConcordant, trimmedLowest, 96, 3.0, 0.12);
	}

	@Test
	void identicalVideosScoreOneHundred() {
		VideoSimilarityProperties properties = properties(3, 1);

		VideoSignature first = signature(10.0, 1920, 1080, concordantFrames(5));
		VideoSignature second = signature(10.0, 1920, 1080, concordantFrames(5));

		assertThat(algorithm(properties).similarityPercent(first, second, 70)).isEqualTo(100);
	}

	@Test
	void rejectsVideosWhoseDurationDiffersBeyondTheTolerance() {
		VideoSimilarityProperties properties = properties(3, 1);

		VideoSignature first = signature(10.0, 1920, 1080, concordantFrames(5));
		VideoSignature second = signature(20.0, 1920, 1080, concordantFrames(5));

		assertThat(algorithm(properties).similarityPercent(first, second, 70)).isEqualTo(-1);
	}

	@Test
	void acceptsSmallDurationDifferencesWithinTheTolerance() {
		VideoSimilarityProperties properties = properties(3, 1);

		VideoSignature first = signature(10.0, 1920, 1080, concordantFrames(5));
		VideoSignature second = signature(12.0, 1920, 1080, concordantFrames(5));

		assertThat(algorithm(properties).similarityPercent(first, second, 70)).isEqualTo(100);
	}

	@Test
	void rejectsVideosWhoseAspectRatioDiffersBeyondTheTolerance() {
		VideoSimilarityProperties properties = properties(3, 1);

		VideoSignature landscape = signature(10.0, 1920, 1080, concordantFrames(5));
		VideoSignature portrait = signature(10.0, 1080, 1920, concordantFrames(5));

		assertThat(algorithm(properties).similarityPercent(landscape, portrait, 70)).isEqualTo(-1);
	}

	@Test
	void rejectsWhenFewerFramesAgreeThanTheQuorum() {
		VideoSimilarityProperties properties = properties(3, 1);

		// 2 aligned frames agree, 3 diverge (opposite hash), quorum is 3.
		VideoSignature first = signature(10.0, 1920, 1080, concordantFrames(5));
		VideoSignature second = signature(10.0, 1920, 1080, framesWithDivergentTail(2, 3));

		assertThat(algorithm(properties).similarityPercent(first, second, 70)).isEqualTo(-1);
	}

	@Test
	void matchesWhenTheQuorumIsMetAndTrimsTheLowestDivergentFrame() {
		VideoSimilarityProperties properties = properties(3, 1);

		// 3 aligned frames agree (100), 2 diverge (0): scores [100,100,100,0,0].
		VideoSignature first = signature(10.0, 1920, 1080, concordantFrames(5));
		VideoSignature second = signature(10.0, 1920, 1080, framesWithDivergentTail(3, 2));

		// Quorum (3) met; trimmed mean drops one 0 -> mean of [100,100,100,0] = 75.
		assertThat(algorithm(properties).similarityPercent(first, second, 70)).isEqualTo(75);
	}

	@Test
	void withoutTrimmingTheSameDivergentFrameDragsTheScoreDown() {
		VideoSimilarityProperties properties = properties(3, 0);

		VideoSignature first = signature(10.0, 1920, 1080, concordantFrames(5));
		VideoSignature second = signature(10.0, 1920, 1080, framesWithDivergentTail(3, 2));

		// No trimming -> mean of [100,100,100,0,0] = 60.
		assertThat(algorithm(properties).similarityPercent(first, second, 70)).isEqualTo(60);
	}

	@Test
	void candidateBucketsCoverNeighboringDurationsButNotDistantOnes() {
		FfmpegLanczosFramesPhashAlgorithm algorithm = algorithm(properties(3, 1));

		Set<Long> near = algorithm.candidateBuckets(signature(10.0, 1920, 1080, concordantFrames(5)));
		Set<Long> alsoNear = algorithm.candidateBuckets(signature(12.0, 1920, 1080, concordantFrames(5)));
		Set<Long> far = algorithm.candidateBuckets(signature(100.0, 1920, 1080, concordantFrames(5)));

		assertThat(near).anyMatch(alsoNear::contains).noneMatch(far::contains);
	}

	@Test
	void videosWithoutDurationShareASingleCatchAllBucket() {
		FfmpegLanczosFramesPhashAlgorithm algorithm = algorithm(properties(3, 1));

		Set<Long> first = algorithm.candidateBuckets(signature(null, 1920, 1080, concordantFrames(5)));
		Set<Long> second = algorithm.candidateBuckets(signature(null, 640, 480, concordantFrames(5)));

		assertThat(first).isEqualTo(second).hasSize(1);
	}

	/**
	 * Duration is a recall-safe pre-filter, so a video whose duration was never
	 * extracted must not be filtered out - it falls through to the frame comparison
	 * instead of being rejected sight unseen.
	 */
	@Test
	void aMissingDurationNeverRejectsThePairOnItsOwn() {
		FfmpegLanczosFramesPhashAlgorithm algorithm = algorithm(properties(3, 1));

		VideoSignature unknown = signature(null, 1920, 1080, concordantFrames(5));
		VideoSignature known = signature(10.0, 1920, 1080, concordantFrames(5));

		assertThat(algorithm.similarityPercent(unknown, known, 70)).isEqualTo(100);
		assertThat(algorithm.similarityPercent(known, unknown, 70)).isEqualTo(100);
	}

	/**
	 * Same for the aspect ratio: absent or nonsensical dimensions mean "cannot
	 * tell", which must not become "not a match".
	 */
	@Test
	void unusableDimensionsNeverRejectThePairOnTheirOwn() {
		FfmpegLanczosFramesPhashAlgorithm algorithm = algorithm(properties(3, 1));

		VideoSignature landscape = signature(10.0, 1920, 1080, concordantFrames(5));

		assertThat(algorithm.similarityPercent(signature(10.0, null, 1080, concordantFrames(5)), landscape, 70))
				.isEqualTo(100);
		assertThat(algorithm.similarityPercent(signature(10.0, 1920, null, concordantFrames(5)), landscape, 70))
				.isEqualTo(100);
		assertThat(algorithm.similarityPercent(signature(10.0, 0, 1080, concordantFrames(5)), landscape, 70))
				.isEqualTo(100);
		assertThat(algorithm.similarityPercent(signature(10.0, 1920, -1, concordantFrames(5)), landscape, 70))
				.isEqualTo(100);
	}

	/**
	 * Frames only compare when both videos sampled the same relative position.
	 * Signatures whose sample indexes do not overlap leave too few aligned frames
	 * to reach the quorum, so the pair is rejected instead of scored on nothing.
	 */
	@Test
	void rejectsWhenTheSampledPositionsDoNotOverlap() {
		FfmpegLanczosFramesPhashAlgorithm algorithm = algorithm(properties(3, 1));

		VideoSignature first = signature(10.0, 1920, 1080, framesAt(0, 1, 2, 3, 4));
		VideoSignature second = signature(10.0, 1920, 1080, framesAt(10, 11, 12, 13, 14));

		assertThat(algorithm.similarityPercent(first, second, 70)).isEqualTo(-1);
	}

	private List<VideoFrameHash> framesAt(int... sampleIndexes) {
		List<VideoFrameHash> frames = new ArrayList<>();

		for (int sampleIndex : sampleIndexes) {
			frames.add(new VideoFrameHash(sampleIndex, HASH_A, LUMA));
		}

		return frames;
	}

	private VideoSignature signature(Double duration, Integer width, Integer height, List<VideoFrameHash> frames) {
		return new VideoSignature(UUID.randomUUID(), frames, duration, width, height);
	}

	private List<VideoFrameHash> concordantFrames(int count) {
		List<VideoFrameHash> frames = new ArrayList<>();

		for (int index = 0; index < count; index++) {
			frames.add(new VideoFrameHash(index, HASH_A, LUMA));
		}

		return frames;
	}

	/**
	 * A signature whose first {@code concordant} frames keep HASH_A (agreeing with
	 * the all-HASH_A other side) and whose last {@code divergent} frames flip to
	 * HASH_B, so those aligned frames are 256 bits apart and score 0.
	 */
	private List<VideoFrameHash> framesWithDivergentTail(int concordant, int divergent) {
		List<VideoFrameHash> frames = new ArrayList<>();

		int index = 0;

		for (int i = 0; i < concordant; i++) {
			frames.add(new VideoFrameHash(index++, HASH_A, LUMA));
		}

		for (int i = 0; i < divergent; i++) {
			frames.add(new VideoFrameHash(index++, HASH_B, LUMA));
		}

		return frames;
	}

	private static byte[] filled(byte value, int length) {
		byte[] bytes = new byte[length];

		Arrays.fill(bytes, value);

		return bytes;
	}
}