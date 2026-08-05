package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static br.com.jorgemelo.nimbusfilemanager.duplicate.application.SyntheticVideoSignatures.DEFAULT_HEIGHT;
import static br.com.jorgemelo.nimbusfilemanager.duplicate.application.SyntheticVideoSignatures.DEFAULT_WIDTH;
import static br.com.jorgemelo.nimbusfilemanager.duplicate.application.SyntheticVideoSignatures.frame;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisResult;

/**
 * An incremental video run has to end where a rebuild would have ended.
 *
 * <p>
 * That is the claim the whole durable-relation design rests on, and it is not
 * self-evident: the placement is greedy and order-dependent, only approvals are
 * stored, and an arrival deliberately never re-asks about a pair of two files
 * that were already covered. Any of those could hide a divergence.
 *
 * <p>
 * <b>The rebuild is the oracle.</b> Every case builds the same library twice,
 * runs a full analysis over one and a sequence of arrivals over the other, and
 * compares the answers. Writing the expected groups by hand would only assert
 * that the expectation and the code were written by the same person on the same
 * afternoon; comparing against the rebuild asserts the property that matters.
 * The few hand-written assertions here are about the <em>shape</em> of a case -
 * that it really does produce two groups, or really does refuse one - so that a
 * fixture which quietly stopped exercising anything would fail instead of
 * agreeing vacuously.
 */
class VideoSimilarityAddEquivalenceTest {

	private static final int THRESHOLD = 70;

	@Test
	void aVideoThatRelatesToNothingChangesNothing() {
		assertAddMatchesRebuild(library -> {
			library.video(1, same(0));
			library.video(2, same(0));
		}, library -> library.video(3, unrelated(1)), THRESHOLD);
	}

	@Test
	void aNewcomerFormsTheFirstGroupWithASingleton() {
		VideoSimilarityLibrary library = library(seed -> {
			seed.video(1, same(0));
			seed.video(2, unrelated(1));
		});

		assertThat(groups(library.service().analyze(THRESHOLD, silent()))).isEmpty();

		library.video(3, same(0));

		assertAdded(library, THRESHOLD, 1);
	}

	@Test
	void aNewcomerJoinsAnExistingGroup() {
		assertAddMatchesRebuild(seed -> {
			seed.video(1, same(0));
			seed.video(2, same(0));
		}, arrival -> arrival.video(3, same(0)), THRESHOLD);
	}

	/**
	 * Complete linkage refuses a candidate that matches one member and not the
	 * other, and the arrival has to refuse it for the same reason - it is the case
	 * a single-linkage shortcut would get wrong.
	 */
	@Test
	void aNewcomerThatDoesNotMatchEveryMemberIsRefused() {
		VideoSimilarityLibrary library = library(seed -> {
			seed.video(1, levels(0, 0, 0, 0, 0));
			seed.video(2, levels(0, 0, 0, 4, 4));
		});

		library.service().analyze(THRESHOLD, silent());

		// It matches the second member and not the first, which is exactly what
		// complete linkage has to refuse - and what single linkage would let in.
		library.video(3, levels(0, 0, 4, 4, 4));

		List<AnalyzedGroup> groups = assertAdded(library, THRESHOLD, 1);

		assertThat(groups.getFirst().members()).as("the pair that already existed, and not the newcomer").hasSize(2);
	}

	@Test
	void aNewcomerCompatibleWithTwoGroupsJoinsTheEarliest() {
		VideoSimilarityLibrary library = library(seed -> {
			seed.video(1, levels(0, 0, 0, 0, 0));
			seed.video(2, levels(0, 0, 0, 0, 0));
			seed.video(3, levels(0, 0, 4, 4, 4));
			seed.video(4, levels(0, 0, 4, 4, 4));
		});

		library.service().analyze(THRESHOLD, silent());

		library.video(5, levels(0, 0, 0, 4, 4));

		List<AnalyzedGroup> groups = assertAdded(library, THRESHOLD, 2);

		assertThat(groups.stream().filter(group -> group.members().size() == 3)).as("one group grew and one did not")
				.hasSize(1);
	}

	@Test
	void severalNewcomersRelatedToEachOtherAndToTheCoveredSet() {
		assertAddMatchesRebuild(seed -> {
			seed.video(1, same(0));
			seed.video(2, unrelated(1));
		}, arrival -> {
			arrival.video(3, same(0));
			arrival.video(4, same(0));
			arrival.video(5, unrelated(2));
		}, THRESHOLD);
	}

	/**
	 * A pair of two covered videos is never asked about again, and the answer is
	 * still the rebuild's - which is the whole economy of the incremental path.
	 */
	@Test
	void neverComparesTwoCoveredVideosAndStillAgrees() {
		VideoSimilarityLibrary library = library(seed -> {
			seed.video(1, same(0));
			seed.video(2, same(0));
			seed.video(3, unrelated(1));
		});

		library.service().analyze(THRESHOLD, silent());

		int afterRebuild = library.relationCount();

		library.video(4, unrelated(2));

		library.service().add(THRESHOLD, silent());

		assertThat(library.relationCount()).as("nothing was re-approved between the videos already covered")
				.isEqualTo(afterRebuild);
		assertThat(library.covered()).containsExactly(1L, 2L, 3L, 4L);
	}

	@Test
	void theDurationGateIsHonouredByBothRoutes() {
		assertAddMatchesRebuild(seed -> seed.video(1, 30.0, DEFAULT_WIDTH, DEFAULT_HEIGHT, same(0)),
				arrival -> arrival.video(2, 90.0, DEFAULT_WIDTH, DEFAULT_HEIGHT, same(0)), THRESHOLD);
	}

	@Test
	void theAspectGateIsHonouredByBothRoutes() {
		assertAddMatchesRebuild(seed -> seed.video(1, 30.0, DEFAULT_WIDTH, DEFAULT_HEIGHT, same(0)),
				arrival -> arrival.video(2, 30.0, DEFAULT_HEIGHT, DEFAULT_WIDTH, same(0)), THRESHOLD);
	}

	@Test
	void videosWithMissingFramesAgree() {
		assertAddMatchesRebuild(seed -> {
			seed.video(1, same(0));
			seed.videoWithFrames(2, new int[] { 0, 2, 4 }, same(0));
		}, arrival -> {
			arrival.videoWithFrames(3, new int[] { 0, 2 }, same(0));
			arrival.videoWithFrames(4, new int[] { 1, 3 }, same(0));
		}, THRESHOLD);
	}

	@ParameterizedTest
	@ValueSource(ints = { 1, 2, 3, 4, 5 })
	void everyFrameCountAgrees(int frames) {
		int[] indexes = new int[frames];

		for (int index = 0; index < frames; index++) {
			indexes[index] = index;
		}

		assertAddMatchesRebuild(seed -> seed.video(1, same(0)),
				arrival -> arrival.videoWithFrames(2, indexes, same(0)), THRESHOLD);
	}

	@ParameterizedTest
	@ValueSource(ints = { 70, 90, 95 })
	void everyThresholdAgrees(int minimum) {
		assertAddMatchesRebuild(seed -> {
			seed.video(1, levels(0, 0, 0, 0, 0));
			seed.video(2, levels(0, 0, 0, 1, 1));
		}, arrival -> {
			arrival.video(3, levels(0, 0, 0, 4, 4));
			arrival.video(4, levels(0, 0, 0, 0, 0));
		}, minimum);
	}

	/**
	 * The quorum at its boundary, reached incrementally: three concordant frames of
	 * five is a relation and two is not, whichever route computed it.
	 */
	@Test
	void theQuorumBoundaryAgrees() {
		assertAddMatchesRebuild(seed -> seed.video(1, levels(0, 0, 0, 0, 0)), arrival -> {
			arrival.video(2, levels(0, 0, 0, 4, 4));
			arrival.video(3, levels(0, 0, 4, 4, 4));
		}, THRESHOLD);
	}

	/**
	 * The score an arrival stores is the score a rebuild stores, which is what lets
	 * the group's floor be read back instead of recomputed.
	 */
	@Test
	void theStoredScoreIsTheRebuildsScore() {
		VideoSimilarityLibrary rebuilt = library(seed -> {
			seed.video(1, levels(0, 0, 0, 1, 1));
			seed.video(2, levels(0, 0, 0, 0, 0));
		});

		rebuilt.service().analyze(THRESHOLD, silent());

		VideoSimilarityLibrary added = library(seed -> seed.video(1, levels(0, 0, 0, 1, 1)));

		added.service().analyze(THRESHOLD, silent());

		added.video(2, levels(0, 0, 0, 0, 0));

		added.service().add(THRESHOLD, silent());

		assertThat(added.scoresOf(1, 2)).as("a real score, not a trivial 100").isNotEmpty()
				.doesNotContain(100).isEqualTo(rebuilt.scoresOf(1, 2));
	}

	/**
	 * A video hidden from the analysis keeps its relations and its coverage, so
	 * bringing it back costs nothing - and the answer is still the rebuild's over
	 * whatever is eligible at the time.
	 */
	@Test
	void hidingAndRestoringAVideoRecomputesNothing() {
		VideoSimilarityLibrary library = library(seed -> {
			seed.video(1, same(0));
			seed.video(2, same(0));
			seed.video(3, unrelated(1));
		});

		library.service().analyze(THRESHOLD, silent());

		int approved = library.relationCount();

		library.hide(2);

		assertThat(groups(library.service().regroup(THRESHOLD, silent()))).as("the pair lost a member").isEmpty();
		assertThat(library.relationCount()).as("the relation is still true and is kept").isEqualTo(approved);

		library.show(2);

		assertThat(groups(library.service().regroup(THRESHOLD, silent()))).hasSize(1);
		assertThat(library.relationCount()).isEqualTo(approved);
	}

	/**
	 * A regroup compares nothing: it never asks the writer to replace a family, and
	 * it never asks the fingerprint store for the rows an arrival would need.
	 */
	@Test
	void aRegroupComparesNothing() {
		VideoSimilarityLibrary library = library(seed -> {
			seed.video(1, same(0));
			seed.video(2, same(0));
		});

		library.service().analyze(THRESHOLD, silent());

		int approved = library.relationCount();

		library.hide(1);
		library.service().regroup(THRESHOLD, silent());

		assertThat(library.relationCount()).isEqualTo(approved);
		assertThat(library.covered()).containsExactly(1L, 2L);
	}

	/**
	 * Re-fingerprinting forgets what was computed from the frames that are gone, so
	 * the video re-enters as a newcomer and the next arrival reaches the answer a
	 * rebuild over the new frames would give.
	 */
	@Test
	void aRefingerprintedVideoIsComparedAgain() {
		VideoSimilarityLibrary library = library(seed -> {
			seed.video(1, same(0));
			seed.video(2, same(0));
		});

		library.service().analyze(THRESHOLD, silent());

		assertThat(library.approvedPairs()).containsExactly("1-2");

		library.refingerprint(2, unrelated(1));

		assertThat(library.covered()).as("it went back to being a file nobody compared").containsExactly(1L);

		library.service().add(THRESHOLD, silent());

		assertThat(library.approvedPairs()).as("the pair that stopped qualifying did not survive").isEmpty();
		assertThat(library.covered()).containsExactly(1L, 2L);
	}

	/**
	 * The end-to-end proof for the inputs that do not live in the fingerprint: a
	 * duration read again forgets the video's relations and its coverage, and the
	 * next arrival recomputes without the pair that stopped qualifying.
	 */
	@Test
	void aDurationThatChangedInvalidatesAndIsRecomputed() {
		assertRemeasureInvalidates(90.0, DEFAULT_WIDTH, DEFAULT_HEIGHT);
	}

	@Test
	void aDisplayShapeThatChangedInvalidatesAndIsRecomputed() {
		assertRemeasureInvalidates(30.0, DEFAULT_HEIGHT, DEFAULT_WIDTH);
	}

	/** Asking twice adds nothing: the second run finds no newcomer and only regroups. */
	@Test
	void aRepeatedAddIsIdempotent() {
		VideoSimilarityLibrary library = library(seed -> {
			seed.video(1, same(0));
			seed.video(2, same(0));
		});

		library.service().analyze(THRESHOLD, silent());

		library.video(3, same(0));

		List<AnalyzedGroup> first = groups(library.service().add(THRESHOLD, silent()));

		int approved = library.relationCount();

		assertThat(groups(library.service().add(THRESHOLD, silent()))).isEqualTo(first);
		assertThat(library.relationCount()).isEqualTo(approved);
	}

	/** Two batches in a row end where one rebuild over the final set ends. */
	@Test
	void twoConsecutiveBatchesConverge() {
		VideoSimilarityLibrary rebuilt = library(seed -> {
			seed.video(1, same(0));
			seed.video(2, same(0));
			seed.video(3, unrelated(1));
			seed.video(4, unrelated(2));
		});

		VideoSimilarityLibrary added = library(seed -> seed.video(1, same(0)));

		added.service().analyze(THRESHOLD, silent());

		added.video(2, same(0));
		added.service().add(THRESHOLD, silent());

		added.video(3, unrelated(1));
		added.video(4, unrelated(2));

		assertThat(groups(added.service().add(THRESHOLD, silent())))
				.isEqualTo(groups(rebuilt.service().analyze(THRESHOLD, silent())));
	}

	/**
	 * Two thresholds are two families with two coverages, and incorporating one
	 * leaves the other untouched.
	 */
	@Test
	void thresholdsCoexistWithoutDisturbingEachOther() {
		VideoSimilarityLibrary library = library(seed -> {
			seed.video(1, same(0));
			seed.video(2, same(0));
		});

		library.service().analyze(70, silent());
		library.service().analyze(95, silent());

		library.video(3, same(0));

		library.service().add(70, silent());

		assertThat(library.covered()).as("both families name every video they incorporated")
				.containsExactly(1L, 2L, 3L);
	}

	/**
	 * Hundreds of shapes, because the divergence a hand-written case misses is the
	 * one nobody thought to write: a cluster that grows past a candidate it would
	 * have accepted, a gate that fires for one member of a group only, an arrival
	 * that lands between two videos already covered.
	 */
	@ParameterizedTest
	@ValueSource(ints = { 70, 90, 95 })
	void agreesOverManyRandomSequences(int minimum) {
		int grouped = 0;

		for (int seed = 0; seed < 40; seed++) {
			Random random = new Random(seed * 71L + minimum);

			int initial = 3 + random.nextInt(4);
			int arriving = 1 + random.nextInt(4);

			VideoSimilarityLibrary rebuilt = new VideoSimilarityLibrary();
			VideoSimilarityLibrary added = new VideoSimilarityLibrary();

			for (int index = 1; index <= initial + arriving; index++) {
				int[] frames = randomFrames(random);

				rebuilt.video(index, frames);

				if (index <= initial) {
					added.video(index, frames);
				}
			}

			added.service().analyze(minimum, silent());

			Random again = new Random(seed * 71L + minimum);

			again.nextInt(4);
			again.nextInt(4);

			for (int index = 1; index <= initial + arriving; index++) {
				int[] frames = randomFrames(again);

				if (index > initial) {
					added.video(index, frames);
				}
			}

			List<AnalyzedGroup> expected = groups(rebuilt.service().analyze(minimum, silent()));

			assertThat(groups(added.service().add(minimum, silent()))).as("threshold %d, seed %d", minimum, seed)
					.isEqualTo(expected);

			if (!expected.isEmpty()) {
				grouped++;
			}
		}

		assertThat(grouped).as("sequences that produced at least one group, so the run is not vacuous")
				.isGreaterThan(5);
	}

	private void assertRemeasureInvalidates(Double duration, Integer width, Integer height) {
		VideoSimilarityLibrary library = library(seed -> {
			seed.video(1, same(0));
			seed.video(2, same(0));
		});

		library.service().analyze(THRESHOLD, silent());

		assertThat(library.approvedPairs()).containsExactly("1-2");

		library.remeasure(2, duration, width, height);

		assertThat(library.covered()).as("the video went back to being one nobody compared").containsExactly(1L);
		assertThat(library.approvedPairs()).as("what was computed from the old measurement is gone").isEmpty();

		library.service().add(THRESHOLD, silent());

		assertThat(library.approvedPairs()).as("the gate refuses it now, so no relation comes back").isEmpty();
		assertThat(library.covered()).containsExactly(1L, 2L);
	}

	/**
	 * The heart of the matrix: the same library twice, one rebuilt in full and one
	 * grown by an arrival, compared as answers rather than as internals.
	 */
	private void assertAddMatchesRebuild(Consumer<VideoSimilarityLibrary> seed,
			Consumer<VideoSimilarityLibrary> arrival, int minimum) {
		VideoSimilarityLibrary rebuilt = new VideoSimilarityLibrary();

		seed.accept(rebuilt);
		arrival.accept(rebuilt);

		VideoSimilarityLibrary added = new VideoSimilarityLibrary();

		seed.accept(added);

		added.service().analyze(minimum, silent());

		arrival.accept(added);

		assertThat(groups(added.service().add(minimum, silent())))
				.isEqualTo(groups(rebuilt.service().analyze(minimum, silent())));
	}

	private List<AnalyzedGroup> assertAdded(VideoSimilarityLibrary library, int minimum, int expectedGroups) {
		List<AnalyzedGroup> groups = groups(library.service().add(minimum, silent()));

		assertThat(groups).as("the fixture has to produce the shape the case is about").hasSize(expectedGroups);

		return groups;
	}

	private VideoSimilarityLibrary library(Consumer<VideoSimilarityLibrary> seed) {
		VideoSimilarityLibrary library = new VideoSimilarityLibrary();

		seed.accept(library);

		return library;
	}

	private List<AnalyzedGroup> groups(SimilarityAnalysisResult result) {
		return result.groups();
	}

	private SimilarityProgressCallback silent() {
		return (_, _) -> {
		};
	}

	private int[] randomFrames(Random random) {
		int[] frames = new int[FfmpegLanczosFramesPhashAlgorithm.FRAME_SAMPLES];

		for (int index = 0; index < frames.length; index++) {
			frames[index] = frame(random.nextInt(2), random.nextInt(2));
		}

		return frames;
	}

	/** Five frames, each of its own group, all at the same level. */
	private int[] same(int level) {
		return levels(level, level, level, level, level);
	}

	/**
	 * Five frames of groups no other video uses, so it relates to nothing - and a
	 * different {@code seed} relates to no other {@code unrelated} either, which
	 * matters whenever two of them share a fixture.
	 */
	private int[] unrelated(int seed) {
		int[] frames = new int[FfmpegLanczosFramesPhashAlgorithm.FRAME_SAMPLES];

		for (int index = 0; index < frames.length; index++) {
			frames[index] = frame(frames.length + seed * frames.length + index, 0);
		}

		return frames;
	}

	private int[] levels(int... levels) {
		int[] frames = new int[levels.length];

		for (int index = 0; index < levels.length; index++) {
			frames[index] = frame(index, levels[index]);
		}

		return frames;
	}

}