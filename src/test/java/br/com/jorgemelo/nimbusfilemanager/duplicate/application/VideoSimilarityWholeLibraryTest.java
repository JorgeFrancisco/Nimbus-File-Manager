package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static br.com.jorgemelo.nimbusfilemanager.duplicate.application.SyntheticVideoSignatures.frame;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.SimilarityConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisResult;

/**
 * The video analysis is about the whole library, and this is the test that would
 * fail if it were not.
 *
 * <p>
 * There used to be a cap of 8.000 eligible videos. It was never derived from a
 * resource - a hundred thousand videos hold 619 MB of frames against the
 * worker's 4 GB - and what it did in practice was truncate: the run analysed the
 * first 8.000 by catalog id and published the answer as though it were about the
 * library. Everything past the cut was invisible, and nothing said so.
 *
 * <p>
 * <b>A test with fewer than 8.001 videos proves nothing here.</b> So this one
 * crosses the old frontier deliberately and puts the only interesting fact on
 * the far side of it: the two videos that relate to each other sit at positions
 * 8.000 and 8.001, and every other video in the library relates to nothing. With
 * the cap in place the pair is cut off, the analysis finds no group, and the
 * assertions below fail. Without it the pair is found.
 *
 * <p>
 * Synthetic and deterministic: the frames are built rather than decoded, so
 * eight thousand videos cost the same as eight.
 */
class VideoSimilarityWholeLibraryTest {

	/** The cap that used to be here, so the fixture can be placed past it. */
	private static final int OLD_CAP = 8_000;

	private static final int THRESHOLD = 70;

	/** Far enough apart that no two filler videos share a duration bucket. */
	private static final double SPREAD_SECONDS = 10.0;

	private static final int WIDTH = 1920;
	private static final int HEIGHT = 1080;

	@Test
	void findsAGroupWhoseMembersSitBeyondTheOldCandidateCap() {
		VideoSimilarityLibrary library = beyondTheOldCap();

		List<AnalyzedGroup> groups = groups(library.service().analyze(THRESHOLD, silent()));

		assertThat(groups).as("the only related pair sits past the old cut, so a truncating run finds nothing")
				.hasSize(1);
		assertThat(groups.getFirst().members()).hasSize(2);

		assertThat(library.approvedPairs()).containsExactly((OLD_CAP + 1) + "-" + (OLD_CAP + 2));
	}

	/** Coverage names every eligible video, not the first few thousand of them. */
	@Test
	void coversTheWholeLibraryAndNotJustItsBeginning() {
		VideoSimilarityLibrary library = beyondTheOldCap();

		library.service().analyze(THRESHOLD, silent());

		assertThat(library.covered()).hasSize(OLD_CAP + 2).contains((long) OLD_CAP + 1, (long) OLD_CAP + 2);
	}

	/**
	 * The published composition says the run was about everything. A partial result
	 * is detected by comparing what was analysed against what was eligible, so a
	 * run that analysed all of it must report the two as equal - otherwise a
	 * complete answer would be shown to the user as a truncated one.
	 */
	@Test
	void reportsTheAnalysisAsCompleteRatherThanCapped() {
		VideoSimilarityLibrary library = beyondTheOldCap();

		SimilarityAnalysisResult result = library.service().analyze(THRESHOLD, silent());

		assertThat(result.composition().analyzedCount()).isEqualTo(result.composition().eligibleCount())
				.isEqualTo(OLD_CAP + 2);
		assertThat(result.composition().candidateLimit()).as("no limit, which is what zero means here")
				.isEqualTo(SimilarityConstants.NO_CANDIDATE_LIMIT);
	}

	/**
	 * An arrival past the frontier is incorporated too - the cap used to decide
	 * which files were eligible, so removing it has to reach the incremental path
	 * and not only the rebuild.
	 */
	@Test
	void incorporatesAnArrivalThatLandsBeyondTheOldCap() {
		VideoSimilarityLibrary library = beyondTheOldCap();

		library.service().analyze(THRESHOLD, silent());

		library.video(OLD_CAP + 3, related());

		assertThat(groups(library.service().add(THRESHOLD, silent())).getFirst().members())
				.as("the arrival joined the group that already sat past the cut").hasSize(3);

		assertThat(library.covered()).hasSize(OLD_CAP + 3);
	}

	/**
	 * A library that crosses the old frontier, whose only related pair is on the
	 * far side of it. Every other video carries frames of its own groups, so it
	 * relates to nothing and cannot rescue the assertion by accident.
	 */
	private VideoSimilarityLibrary beyondTheOldCap() {
		VideoSimilarityLibrary library = new VideoSimilarityLibrary();

		// A duration of its own for each filler video, which is what a real library
		// looks like and what keeps this test quick: the bucket gate then rejects the
		// thirty-two million filler pairs on a set intersection instead of walking
		// five frames each. The pair that matters keeps the shared duration, so the
		// only work of any size is the work the assertions are about.
		for (int index = 1; index <= OLD_CAP; index++) {
			library.video(index, SPREAD_SECONDS * index, WIDTH, HEIGHT, unrelated(index));
		}

		library.video(OLD_CAP + 1, related());
		library.video(OLD_CAP + 2, related());

		return library;
	}

	/** The frames the pair beyond the frontier shares. */
	private int[] related() {
		int[] frames = new int[FfmpegLanczosFramesPhashAlgorithm.FRAME_SAMPLES];

		for (int index = 0; index < frames.length; index++) {
			frames[index] = frame(index, 0);
		}

		return frames;
	}

	/**
	 * Frames of groups nobody else uses, so this video relates to nothing. The
	 * group index is derived from the catalog id, which keeps the whole library
	 * mutually unrelated without a lookup.
	 */
	private int[] unrelated(int catalogFileId) {
		int[] frames = new int[FfmpegLanczosFramesPhashAlgorithm.FRAME_SAMPLES];

		for (int index = 0; index < frames.length; index++) {
			frames[index] = frame(frames.length + catalogFileId * frames.length + index, 0);
		}

		return frames;
	}

	private List<AnalyzedGroup> groups(SimilarityAnalysisResult result) {
		return result.groups();
	}

	private SimilarityProgressCallback silent() {
		return (_, _) -> {
		};
	}
}