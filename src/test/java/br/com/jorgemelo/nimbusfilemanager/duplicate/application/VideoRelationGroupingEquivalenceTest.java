package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static br.com.jorgemelo.nimbusfilemanager.duplicate.application.SyntheticVideoSignatures.DEFAULT_HEIGHT;
import static br.com.jorgemelo.nimbusfilemanager.duplicate.application.SyntheticVideoSignatures.DEFAULT_WIDTH;
import static br.com.jorgemelo.nimbusfilemanager.duplicate.application.SyntheticVideoSignatures.frame;
import static br.com.jorgemelo.nimbusfilemanager.duplicate.application.SyntheticVideoSignatures.video;
import static br.com.jorgemelo.nimbusfilemanager.duplicate.application.SyntheticVideoSignatures.withFrames;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.PairKey;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoSignature;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.PerceptualHashCodec;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.VideoSimilarityProperties;

/**
 * Before video relations may be stored, the two ways of reaching a grouping have
 * to reach the same one.
 *
 * <p>
 * The path in use compares lazily: the grouping calls a scorer from inside its
 * own loop, and the scorer is the bucket gate followed by
 * {@code similarityPercent}. The path being proposed compares first - every pair
 * once, into {@code (first, second, score)} - and groups from those relations.
 * If the two disagree anywhere, persisting relations would change answers while
 * claiming to be a change of structure.
 *
 * <p>
 * <b>There is one implementation of the rule, and both paths ask it.</b> Neither
 * side re-derives what makes two videos similar: the real
 * {@link FfmpegLanczosFramesPhashAlgorithm} decides, and what is being compared
 * is the two ways of arranging its answers. A test that scored the pairs itself
 * would prove the test agrees with itself.
 *
 * <p>
 * The fixtures are built rather than sampled - see
 * {@link SyntheticVideoSignatures} - because the cases that separate the two
 * groupings are the awkward ones: a candidate that fits two clusters, a cluster
 * that stops fitting once it grows, a pair that only survives because the
 * trimmed mean discards its worst frame.
 */
class VideoRelationGroupingEquivalenceTest {

	private static final int THRESHOLD = 70;

	private final VideoSimilarityProperties properties = new VideoSimilarityProperties(null, null, null, null, null);
	private final FfmpegLanczosFramesPhashAlgorithm algorithm = new FfmpegLanczosFramesPhashAlgorithm(null,
			new LuminanceSsimService(), properties);

	/**
	 * The premise the whole fixture rests on: two groups are further apart than any
	 * radius the comparison would use, so the group alone decides whether a frame
	 * survives the cheap filter.
	 */
	@Test
	void hashesOfDifferentGroupsAreOutsideAnyUsableRadius() {
		int radius = properties.maxFrameHashDistanceOrDefault();

		for (int first = 0; first < 12; first++) {
			for (int second = first + 1; second < 12; second++) {
				assertThat(PerceptualHashCodec.distance(SyntheticVideoSignatures.hash(first),
						SyntheticVideoSignatures.hash(second))).as("groups %d and %d", first, second)
						.isGreaterThan(radius);
			}
		}
	}

	@Test
	void agreesWhenNoPairIsApproved() {
		List<VideoSignature> videos = List.of(identical(1, 0), identical(2, 4));

		assertThat(currentPath(videos, THRESHOLD)).isEmpty();
		assertBothPathsAgree(videos, THRESHOLD);
	}

	@Test
	void agreesOnASinglePair() {
		List<VideoSignature> videos = List.of(identical(1, 0), identical(2, 0), identical(3, 4));

		assertThat(currentPath(videos, THRESHOLD)).containsExactly(List.of(0, 1));
		assertBothPathsAgree(videos, THRESHOLD);
	}

	/**
	 * The case complete linkage exists for: the middle relates to both ends and the
	 * ends do not relate to each other, so the three may not share a group.
	 */
	@Test
	void agreesWhenAChainMustNotBecomeAGroup() {
		List<VideoSignature> videos = List.of(levels(1, 0, 0, 0, 0, 0), levels(2, 0, 0, 0, 4, 4),
				levels(3, 0, 0, 4, 4, 4));

		assertThat(approved(videos, 0, 1, THRESHOLD)).isTrue();
		assertThat(approved(videos, 1, 2, THRESHOLD)).isTrue();
		assertThat(approved(videos, 0, 2, THRESHOLD)).isFalse();

		assertThat(currentPath(videos, THRESHOLD)).containsExactly(List.of(0, 1));
		assertBothPathsAgree(videos, THRESHOLD);
	}

	@Test
	void agreesOnACompleteTriangle() {
		List<VideoSignature> videos = List.of(identical(1, 0), identical(2, 0), identical(3, 0));

		assertThat(currentPath(videos, THRESHOLD)).containsExactly(List.of(0, 1, 2));
		assertBothPathsAgree(videos, THRESHOLD);
	}

	/**
	 * A candidate that fits two clusters joins the one created first. That is what
	 * "stop at the first match" means in the lazy path, and it is the property most
	 * easily lost when clusters are reached through a map.
	 */
	@Test
	void agreesWhenACandidateFitsMoreThanOneGroup() {
		List<VideoSignature> videos = List.of(levels(1, 0, 0, 0, 0, 0), levels(2, 0, 0, 0, 0, 0),
				levels(3, 0, 0, 4, 4, 4), levels(4, 0, 0, 4, 4, 4), levels(5, 0, 0, 0, 4, 4));

		assertThat(approved(videos, 4, 0, THRESHOLD)).as("fits the first group").isTrue();
		assertThat(approved(videos, 4, 2, THRESHOLD)).as("fits the second group too").isTrue();
		assertThat(approved(videos, 0, 2, THRESHOLD)).as("the two groups are apart").isFalse();

		assertThat(currentPath(videos, THRESHOLD)).containsExactly(List.of(0, 1, 4), List.of(2, 3));
		assertBothPathsAgree(videos, THRESHOLD);
	}

	/**
	 * Two pairs that score the same, and a third that scores differently: the tie
	 * decides nothing on its own, and the group's floor has to come out the same
	 * number on both paths.
	 */
	@Test
	void agreesOnTiedAndOnDifferingScores() {
		List<VideoSignature> videos = List.of(levels(1, 0, 0, 0, 0, 0), levels(2, 0, 0, 0, 0, 0),
				levels(3, 0, 0, 0, 1, 1));

		assertThat(score(videos, 0, 2, THRESHOLD)).as("two pairs the same distance apart tie")
				.isEqualTo(score(videos, 1, 2, THRESHOLD));
		assertThat(score(videos, 0, 2, THRESHOLD)).as("a different distance scores differently")
				.isNotEqualTo(score(videos, 0, 1, THRESHOLD));

		assertBothPathsAgree(videos, THRESHOLD);
		assertWorstScoresAgree(videos, THRESHOLD);
	}

	/**
	 * The quorum, at the boundary: three concordant frames of five is a relation and
	 * two is not, and nothing about that changes when the pairs are computed ahead
	 * of the grouping.
	 */
	@Test
	void agreesAtTheQuorumBoundary() {
		List<VideoSignature> videos = List.of(levels(1, 0, 0, 0, 0, 0), levels(2, 0, 0, 0, 4, 4),
				levels(3, 0, 0, 4, 4, 4));

		assertThat(concordant(videos, 0, 1, THRESHOLD)).as("three of five").isEqualTo(3);
		assertThat(concordant(videos, 0, 2, THRESHOLD)).as("two of five").isEqualTo(2);

		assertThat(approved(videos, 0, 1, THRESHOLD)).isTrue();
		assertThat(approved(videos, 0, 2, THRESHOLD)).isFalse();

		assertBothPathsAgree(videos, THRESHOLD);
	}

	/**
	 * The trimmed mean is what admits this pair: three frames match and two do not,
	 * so the plain average is below the threshold and the trimmed one is above it.
	 * A relation-driven path that dropped the trimming would silently lose the pair.
	 */
	@Test
	void agreesWhereOnlyTheTrimmedMeanAdmitsThePair() {
		List<VideoSignature> videos = List.of(levels(1, 0, 0, 0, 0, 0), levels(2, 0, 0, 0, 4, 4));

		assertThat(score(videos, 0, 1, THRESHOLD)).as("mean of the four kept frames, worst discarded")
				.isGreaterThanOrEqualTo(THRESHOLD);
		assertThat(untrimmedMean(videos, 0, 1)).as("the plain average would have refused it").isLessThan(THRESHOLD);

		assertBothPathsAgree(videos, THRESHOLD);
	}

	/** Beyond the tolerance the pair never reaches the frames, on either path. */
	@Test
	void agreesWhenTheDurationsAreIncompatible() {
		List<VideoSignature> videos = List.of(video(1, 30.0, DEFAULT_WIDTH, DEFAULT_HEIGHT, sameFrames(0)),
				video(2, 40.0, DEFAULT_WIDTH, DEFAULT_HEIGHT, sameFrames(0)));

		assertThat(currentPath(videos, THRESHOLD)).isEmpty();
		assertBothPathsAgree(videos, THRESHOLD);
	}

	/**
	 * The gap between the two duration filters, which is the one place the bucket
	 * gate is not the whole story: these two share an adjacent bucket, so the cheap
	 * gate lets them through, and the exact tolerance is what refuses them.
	 */
	@Test
	void agreesWhenTheBucketAdmitsADurationTheToleranceRefuses() {
		List<VideoSignature> videos = List.of(video(1, 30.0, DEFAULT_WIDTH, DEFAULT_HEIGHT, sameFrames(0)),
				video(2, 33.5, DEFAULT_WIDTH, DEFAULT_HEIGHT, sameFrames(0)));

		assertThat(sharesABucket(videos.get(0), videos.get(1))).as("the cheap gate lets them through").isTrue();

		assertThat(currentPath(videos, THRESHOLD)).isEmpty();
		assertBothPathsAgree(videos, THRESHOLD);
	}

	@Test
	void agreesWhenTheAspectRatiosAreIncompatible() {
		List<VideoSignature> videos = List.of(video(1, 30.0, DEFAULT_WIDTH, DEFAULT_HEIGHT, sameFrames(0)),
				video(2, 30.0, DEFAULT_HEIGHT, DEFAULT_WIDTH, sameFrames(0)));

		assertThat(currentPath(videos, THRESHOLD)).isEmpty();
		assertBothPathsAgree(videos, THRESHOLD);
	}

	/**
	 * A fingerprint missing frames aligns on what both videos have, and the quorum
	 * follows it down rather than refusing the pair for being short.
	 */
	@Test
	void agreesWhenFramesAreMissing() {
		List<VideoSignature> videos = List.of(levels(1, 0, 0, 0, 0, 0), withFrames(levels(2, 0, 0, 0, 0, 0), 0, 2, 4),
				withFrames(levels(3, 0, 0, 0, 0, 0), 0, 2), withFrames(levels(4, 0, 0, 0, 0, 0), 1, 3));

		assertThat(approved(videos, 1, 3, THRESHOLD)).as("no sample index in common, so nothing to align")
				.isFalse();

		assertThat(currentPath(videos, THRESHOLD)).containsExactly(List.of(0, 1, 2));
		assertBothPathsAgree(videos, THRESHOLD);
	}

	/** Videos of different frame counts, which is what the real library holds. */
	@Test
	void agreesWhenTheFrameCountsDiffer() {
		List<VideoSignature> videos = List.of(levels(1, 0, 0, 0, 0, 0), withFrames(levels(2, 0, 0, 0, 0, 0), 0, 1, 2),
				withFrames(levels(3, 0, 0, 0, 0, 0), 0), withFrames(levels(4, 4, 4, 4, 4, 4), 0, 1));

		assertBothPathsAgree(videos, THRESHOLD);
		assertBothPathsAgree(videos, 90);
		assertBothPathsAgree(videos, 95);
	}

	/**
	 * The placement is greedy and depends on the order candidates are visited in,
	 * which is {@code catalog_file.id} ascending. Both paths have to depend on it
	 * the same way - so the same set in a different order changes the answer, and
	 * changes it identically on both sides.
	 */
	@Test
	void agreesOnTheOrderTheGreedyPlacementDependsOn() {
		VideoSignature left = levels(1, 0, 0, 0, 0, 0);
		VideoSignature middle = levels(2, 0, 0, 0, 4, 4);
		VideoSignature right = levels(3, 0, 0, 4, 4, 4);

		List<VideoSignature> ascending = List.of(left, middle, right);
		List<VideoSignature> reversed = List.of(right, middle, left);

		assertThat(groupedIds(ascending)).containsExactly(List.of(1L, 2L));
		assertThat(groupedIds(reversed)).as("the visiting order decides which of the two pairs survives")
				.containsExactly(List.of(3L, 2L));

		assertBothPathsAgree(ascending, THRESHOLD);
		assertBothPathsAgree(reversed, THRESHOLD);
	}

	/** The current path's groups, named by catalog id and not by position. */
	private List<List<Long>> groupedIds(List<VideoSignature> videos) {
		return currentPath(videos, THRESHOLD).stream()
				.map(group -> group.stream().map(index -> videos.get(index).id().getLeastSignificantBits()).toList())
				.toList();
	}

	/**
	 * Hundreds of shapes at each threshold, because the disagreements a hand-written
	 * case misses are the ones nobody thought to write: a cluster that grows past a
	 * candidate it would have accepted earlier, a pair approved in one direction and
	 * asked about in the other, a gate that fires for one member of a group only.
	 */
	@ParameterizedTest
	@ValueSource(ints = { 70, 90, 95 })
	void agreesOverHundredsOfRandomPopulations(int minimum) {
		int grouped = 0;

		for (int seed = 0; seed < 200; seed++) {
			List<VideoSignature> videos = population(seed);

			List<List<Integer>> expected = currentPath(videos, minimum);

			assertThat(relationPath(videos, minimum)).as("threshold %d, seed %d", minimum, seed).isEqualTo(expected);

			if (!expected.isEmpty()) {
				grouped++;
			}
		}

		assertThat(grouped).as("populations that produced at least one group, so the run is not vacuous")
				.isGreaterThan(20);
	}

	@ParameterizedTest
	@ValueSource(ints = { 70, 90, 95 })
	void reportsTheSameGroupFloorFromRelationsAsFromTheScorer(int minimum) {
		for (int seed = 0; seed < 60; seed++) {
			assertWorstScoresAgree(population(seed), minimum);
		}
	}

	/**
	 * A population of the shapes the comparison actually meets: frames that match
	 * and frames that do not, durations inside and outside the tolerance, portrait
	 * among landscape, and fingerprints that lost a frame.
	 */
	private List<VideoSignature> population(int seed) {
		Random random = new Random(seed * 31L + 17);

		int size = 8 + random.nextInt(9);

		List<int[]> codes = new ArrayList<>(size);
		List<VideoSignature> videos = new ArrayList<>(size);

		for (int index = 0; index < size; index++) {
			int[] frames = codes.isEmpty() || random.nextInt(3) > 0 ? unrelated(random)
					: nudged(codes.get(random.nextInt(codes.size())), random);

			codes.add(frames);

			VideoSignature video = video(index + 1L, 30.0 + random.nextInt(4) * 2.5,
					random.nextInt(4) == 0 ? DEFAULT_HEIGHT : DEFAULT_WIDTH, DEFAULT_HEIGHT, frames);

			videos.add(random.nextInt(5) == 0 ? withFrames(video, 0, 2, 3) : video);
		}

		return videos;
	}

	/** A video of its own, whose frames match another's only by chance. */
	private int[] unrelated(Random random) {
		int[] frames = new int[FfmpegLanczosFramesPhashAlgorithm.FRAME_SAMPLES];

		for (int position = 0; position < frames.length; position++) {
			frames[position] = frame(random.nextInt(3), random.nextInt(SyntheticVideoSignatures.LEVELS));
		}

		return frames;
	}

	/**
	 * A re-encode of one already in the population: the same frames, at most one of
	 * them drifted by a level. Without these the random populations would group
	 * almost nothing at the higher thresholds, and a run that finds no group proves
	 * only that both paths found none.
	 */
	private int[] nudged(int[] source, Random random) {
		int[] frames = source.clone();

		if (random.nextBoolean()) {
			int position = random.nextInt(frames.length);

			frames[position] = frames[position] % SyntheticVideoSignatures.LEVELS == SyntheticVideoSignatures.LEVELS - 1
					? frames[position] - 1
					: frames[position] + 1;
		}

		return frames;
	}

	/**
	 * The grouping as it happens today: the lazy scorer inside the clustering loop,
	 * memoized per pair, gated by the duration buckets - which is
	 * {@code VideoSimilarityService.score} and {@code group} put together.
	 */
	private List<List<Integer>> currentPath(List<VideoSignature> videos, int minimum) {
		Map<UUID, Set<Long>> buckets = buckets(videos);
		Map<PairKey, Integer> memo = new HashMap<>();

		return SimilarityCompleteLinkageGrouper.cluster(positions(videos), minimum,
				(first, second) -> lazyScore(videos, buckets, memo, first, second, minimum), (_, _) -> {
				});
	}

	/** The grouping from relations computed ahead of it - what is proposed. */
	private List<List<Integer>> relationPath(List<VideoSignature> videos, int minimum) {
		BuiltRelations built = new VideoRelationBuilder(algorithm).build(videos, minimum, (_, _) -> {
		});

		return SimilarityRelationGrouper.cluster(videos.size(), built.relations(), (_, _) -> {
		});
	}

	private void assertBothPathsAgree(List<VideoSignature> videos, int minimum) {
		assertThat(relationPath(videos, minimum)).as("threshold %d", minimum).isEqualTo(currentPath(videos, minimum));
	}

	/**
	 * The floor shown for each group, taken from the memoized scorer and from the
	 * stored relations. Every pair inside a complete-linkage group was approved to
	 * get there, so the relations hold all of them and no frame is read again.
	 */
	private void assertWorstScoresAgree(List<VideoSignature> videos, int minimum) {
		Map<UUID, Set<Long>> buckets = buckets(videos);
		Map<PairKey, Integer> memo = new HashMap<>();

		ApprovedRelations relations = new VideoRelationBuilder(algorithm).build(videos, minimum, (_, _) -> {
		}).relations();

		for (List<Integer> group : currentPath(videos, minimum)) {
			int fromScorer = SimilarityCompleteLinkageGrouper.worstScore(group,
					(first, second) -> lazyScore(videos, buckets, memo, first, second, minimum));

			assertThat(SimilarityCompleteLinkageGrouper.worstScore(group, relations::scoreOf))
					.as("floor of %s at threshold %d", group, minimum).isEqualTo(fromScorer);
		}
	}

	private int lazyScore(List<VideoSignature> videos, Map<UUID, Set<Long>> buckets, Map<PairKey, Integer> memo,
			int first, int second, int minimum) {
		VideoSignature left = videos.get(first);
		VideoSignature right = videos.get(second);

		if (Collections.disjoint(buckets.get(left.id()), buckets.get(right.id()))) {
			return -1;
		}

		return memo.computeIfAbsent(PairKey.of(left.id(), right.id()),
				_ -> algorithm.similarityPercent(left, right, minimum));
	}

	private Map<UUID, Set<Long>> buckets(List<VideoSignature> videos) {
		Map<UUID, Set<Long>> buckets = new HashMap<>();

		for (VideoSignature video : videos) {
			buckets.put(video.id(), algorithm.candidateBuckets(video));
		}

		return buckets;
	}

	private boolean sharesABucket(VideoSignature first, VideoSignature second) {
		return !Collections.disjoint(algorithm.candidateBuckets(first), algorithm.candidateBuckets(second));
	}

	private List<Integer> positions(List<VideoSignature> videos) {
		List<Integer> positions = new ArrayList<>(videos.size());

		for (int index = 0; index < videos.size(); index++) {
			positions.add(index);
		}

		return positions;
	}

	private int score(List<VideoSignature> videos, int first, int second, int minimum) {
		return algorithm.similarityPercent(videos.get(first), videos.get(second), minimum);
	}

	private boolean approved(List<VideoSignature> videos, int first, int second, int minimum) {
		return score(videos, first, second, minimum) >= minimum;
	}

	/**
	 * How many aligned frames reach the threshold, read from the frames themselves
	 * so a quorum assertion states the count rather than implying it.
	 */
	private int concordant(List<VideoSignature> videos, int first, int second, int minimum) {
		int concordant = 0;

		for (int score : frameScores(videos.get(first), videos.get(second))) {
			if (score >= minimum) {
				concordant++;
			}
		}

		return concordant;
	}

	/** What the pair would have scored had the worst frames not been discarded. */
	private int untrimmedMean(List<VideoSignature> videos, int first, int second) {
		int[] scores = frameScores(videos.get(first), videos.get(second));

		double sum = 0;

		for (int score : scores) {
			sum += score;
		}

		return (int) Math.round(sum / scores.length);
	}

	/**
	 * The per-frame scores of a pair, obtained by asking the production algorithm
	 * about one frame at a time: a one-frame video's quorum is one, and its trimmed
	 * mean is the single score, so the answer is the frame's own.
	 */
	private int[] frameScores(VideoSignature first, VideoSignature second) {
		int[] scores = new int[Math.min(first.frames().size(), second.frames().size())];

		for (int index = 0; index < scores.length; index++) {
			scores[index] = algorithm.similarityPercent(withFrames(first, index), withFrames(second, index), 0);
		}

		return scores;
	}

	private VideoSignature identical(long id, int level) {
		return video(id, sameFrames(level));
	}

	private VideoSignature levels(long id, int... levels) {
		int[] frames = new int[levels.length];

		for (int index = 0; index < levels.length; index++) {
			frames[index] = frame(index, levels[index]);
		}

		return video(id, frames);
	}

	/** Five frames, each of its own group, all at the same level. */
	private int[] sameFrames(int level) {
		int[] frames = new int[FfmpegLanczosFramesPhashAlgorithm.FRAME_SAMPLES];

		for (int index = 0; index < frames.length; index++) {
			frames[index] = frame(index, level);
		}

		return frames;
	}
}