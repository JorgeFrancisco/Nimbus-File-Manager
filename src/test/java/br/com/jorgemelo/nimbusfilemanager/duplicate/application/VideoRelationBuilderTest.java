package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static br.com.jorgemelo.nimbusfilemanager.duplicate.application.SyntheticVideoSignatures.DEFAULT_HEIGHT;
import static br.com.jorgemelo.nimbusfilemanager.duplicate.application.SyntheticVideoSignatures.DEFAULT_WIDTH;
import static br.com.jorgemelo.nimbusfilemanager.duplicate.application.SyntheticVideoSignatures.frame;
import static br.com.jorgemelo.nimbusfilemanager.duplicate.application.SyntheticVideoSignatures.video;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoSignature;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.VideoSimilarityProperties;

/**
 * What the video producer hands over, as opposed to what the grouping then makes
 * of it.
 *
 * <p>
 * The shape is a contract with everything downstream: the storage writes the
 * three parallel arrays, the adjacency answers the grouping, and the score is
 * the number the screen shows as a group's floor. So the pairs have to be the
 * ones the algorithm approves, spelled in the order the database accepts, with
 * the score the algorithm returned and nothing else in the table.
 */
class VideoRelationBuilderTest {

	private static final int THRESHOLD = 70;

	private final VideoSimilarityProperties properties = new VideoSimilarityProperties(null, null, null, null, null);
	private final FfmpegLanczosFramesPhashAlgorithm algorithm = new FfmpegLanczosFramesPhashAlgorithm(null,
			new LuminanceSsimService(), properties);

	private final VideoRelationBuilder builder = new VideoRelationBuilder(algorithm);

	@Test
	void keepsOnlyTheApprovedPairsAndTheScoreTheAlgorithmReturned() {
		List<VideoSignature> videos = List.of(same(1), same(2), different(3));

		BuiltRelations built = build(videos);

		assertThat(built.count()).as("one approval out of three pairs").isEqualTo(1);
		assertThat(built.first()[0]).isZero();
		assertThat(built.second()[0]).isEqualTo(1);
		assertThat(built.scores()[0]).isEqualTo(algorithm.similarityPercent(videos.get(0), videos.get(1), THRESHOLD));
	}

	/**
	 * The pair is written with the lower position first, which is what the database
	 * check constraint demands and what makes the reversed spelling impossible
	 * rather than merely discouraged.
	 */
	@Test
	void spellsEveryPairWithTheLowerPositionFirst() {
		List<VideoSignature> videos = List.of(same(1), same(2), same(3), same(4));

		BuiltRelations built = build(videos);

		assertThat(built.count()).as("every pair of four approved").isEqualTo(6);

		for (int index = 0; index < built.count(); index++) {
			assertThat(built.first()[index]).as("pair %d", index).isLessThan(built.second()[index]);
		}
	}

	/**
	 * A rejected pair leaves nothing behind. The grouping cannot tell a rejection
	 * from an absence - outside the radius, scored below the threshold and never
	 * compared are one answer to it - so storing rejections would be rows nobody
	 * reads.
	 */
	@Test
	void writesNothingForAPairItRefuses() {
		BuiltRelations built = build(List.of(same(1), different(2)));

		assertThat(built.count()).isZero();
		assertThat(built.relations().approved(0, 1)).isFalse();
		assertThat(built.relations().scoreOf(0, 1)).as("the contract the lazy scorer had").isNegative();
	}

	@Test
	void indexesEveryCandidateEvenWhenNoneRelate() {
		BuiltRelations built = build(List.of(same(1), different(2), same(3)));

		assertThat(built.relations().degree(1)).as("a video with no approved neighbour").isZero();
		assertThat(built.relations().degree(0)).isEqualTo(1);
	}

	/**
	 * The threshold reaches the algorithm, where it decides the quorum as well as
	 * the final comparison - so raising it removes pairs rather than merely hiding
	 * them.
	 */
	@Test
	void carriesTheThresholdIntoTheComparison() {
		List<VideoSignature> videos = List.of(levels(1, 0, 0, 0, 0, 0), levels(2, 0, 0, 0, 4, 4));

		assertThat(builder.build(videos, THRESHOLD, (_, _) -> {
		}).count()).as("admitted by the trimmed mean at 70").isEqualTo(1);

		assertThat(builder.build(videos, 95, (_, _) -> {
		}).count()).as("refused at 95").isZero();
	}

	/**
	 * A video without a duration buckets alone, so it is never compared - even
	 * though the duration gate on its own would have admitted it against anything.
	 *
	 * <p>
	 * This is the behaviour of the analysis in use, reproduced deliberately rather
	 * than inherited by accident: the bucket gate runs before the comparison there
	 * too. It is written down here because it is the one place where the cheap gate
	 * decides something the comparison would have decided differently, and a future
	 * change to it would be a change of results, not of structure.
	 */
	@Test
	void neverRelatesAVideoThatHasNoDuration() {
		List<VideoSignature> videos = List.of(video(1, null, DEFAULT_WIDTH, DEFAULT_HEIGHT, sameFrames()),
				video(2, 30.0, DEFAULT_WIDTH, DEFAULT_HEIGHT, sameFrames()));

		assertThat(algorithm.similarityPercent(videos.get(0), videos.get(1), THRESHOLD))
				.as("the comparison alone would have approved it").isGreaterThanOrEqualTo(THRESHOLD);

		assertThat(build(videos).count()).as("the bucket gate refuses it first").isZero();
	}

	/** Two videos that both lack a duration share a bucket, so they do meet. */
	@Test
	void relatesTwoVideosThatBothLackADuration() {
		List<VideoSignature> videos = List.of(video(1, null, DEFAULT_WIDTH, DEFAULT_HEIGHT, sameFrames()),
				video(2, null, DEFAULT_WIDTH, DEFAULT_HEIGHT, sameFrames()));

		assertThat(build(videos).count()).isEqualTo(1);
	}

	@Test
	void reportsProgressToTheEndSoACancelCanBeNoticed() {
		List<int[]> reports = new ArrayList<>();

		builder.build(List.of(same(1), same(2)), THRESHOLD, (done, total) -> reports.add(new int[] { done, total }));

		assertThat(reports.getFirst()).containsExactly(0, 2);
		assertThat(reports.getLast()).containsExactly(2, 2);
	}

	private BuiltRelations build(List<VideoSignature> videos) {
		return builder.build(videos, THRESHOLD, (_, _) -> {
		});
	}

	private VideoSignature same(long id) {
		return video(id, sameFrames());
	}

	private VideoSignature different(long id) {
		int[] frames = new int[FfmpegLanczosFramesPhashAlgorithm.FRAME_SAMPLES];

		for (int index = 0; index < frames.length; index++) {
			frames[index] = frame(index + frames.length, 0);
		}

		return video(id, frames);
	}

	private VideoSignature levels(long id, int... levels) {
		int[] frames = new int[levels.length];

		for (int index = 0; index < levels.length; index++) {
			frames[index] = frame(index, levels[index]);
		}

		return video(id, frames);
	}

	private int[] sameFrames() {
		int[] frames = new int[FfmpegLanczosFramesPhashAlgorithm.FRAME_SAMPLES];

		for (int index = 0; index < frames.length; index++) {
			frames[index] = frame(index, 0);
		}

		return frames;
	}
}