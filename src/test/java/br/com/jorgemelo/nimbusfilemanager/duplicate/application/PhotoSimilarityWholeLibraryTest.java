package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedMember;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisResult;

/**
 * The library is analysed whole - there is no candidate cap left in the photo
 * path - and the route a run takes is decided by which one is cheaper.
 *
 * <p>
 * The first tests here are deliberately built with <b>8.001 photos</b>, which is
 * one more than the cap that used to exist. Anything smaller could not tell a
 * removed limit from a limit that simply was not reached, and that is the whole
 * claim: the file at position 8.001 is analysed, and a pair that lives entirely
 * past the old boundary forms a group.
 *
 * <p>
 * It stays fast because the fixture aims the two filters separately. Photos are
 * given hashes from unrelated seeds, so the distance scan rejects essentially
 * every pair and SSIM is asked about almost nothing - the 32 million distance
 * comparisons are arithmetic over a flat array and cost milliseconds. Only the
 * pair the test is about is placed in the same hash neighbourhood.
 */
class PhotoSimilarityWholeLibraryTest {

	private static final int MINIMUM = 70;

	/** One more than the cap that used to truncate the analysis. */
	private static final int PAST_THE_OLD_CAP = 8001;

	private static final long NEARBY = 7;
	private static final long ALPHA = 101;
	private static final long BETA = 202;

	private static final SimilarityProgressCallback SILENT = (_, _) -> {
	};

	private final PhotoSimilarityLibrary library = new PhotoSimilarityLibrary();

	/**
	 * A library larger than the old cap is analysed entirely, and the two photos
	 * that look alike at the very end of it are grouped.
	 *
	 * <p>
	 * Under the cap this was the defect: the run took the first 8.000 catalog ids
	 * and everything after them was never compared with anything, so this pair
	 * could not be found however many times the analysis was run.
	 */
	@Test
	void aLibraryLargerThanTheOldCapIsAnalysedEntirelyAndGroupsThePairBeyondIt() {
		unrelated(1, PAST_THE_OLD_CAP - 2);

		// The last two, in one hash neighbourhood and with the same appearance.
		alike(PAST_THE_OLD_CAP - 1, ALPHA);
		alike(PAST_THE_OLD_CAP, ALPHA);

		SimilarityAnalysisResult result = library.service().analyze(MINIMUM, SILENT);

		assertThat(result.composition().analyzedCount()).as("every eligible file, not the first 8.000")
				.isEqualTo(PAST_THE_OLD_CAP);
		assertThat(result.composition().eligibleCount()).isEqualTo(PAST_THE_OLD_CAP);
		assertThat(groupsOf(result)).as("the pair past the old boundary")
				.containsExactly(Set.of(publicId(PAST_THE_OLD_CAP - 1), publicId(PAST_THE_OLD_CAP)));
	}

	/**
	 * And the run says so: nothing announces a limit, so the screen has no partial
	 * result to explain.
	 */
	@Test
	void anUncappedRunReportsNoCandidateLimitAndACompleteCoverage() {
		unrelated(1, 10);

		SimilarityAnalysisResult result = library.service().analyze(MINIMUM, SILENT);

		assertThat(result.composition().candidateLimit()).isZero();
		assertThat(result.composition().analyzedCount()).isEqualTo(result.composition().eligibleCount());
	}

	/**
	 * The coverage a rebuild leaves names every file it analysed - which is what
	 * lets the next arrival compare itself against the whole library instead of
	 * against the first 8.000 of it.
	 */
	@Test
	void theCoverageAfterARebuildNamesEveryFileAnalysed() {
		unrelated(1, 50);

		library.service().analyze(MINIMUM, SILENT);

		assertThat(library.covered()).hasSize(50);
		assertThat(library.covered()).contains(1L, 50L);
	}

	/**
	 * <b>The upgrade.</b> A family that had covered a truncated slice meets a
	 * library where almost nothing is covered: the newcomers outnumber the covered
	 * files, so the run takes the rebuild rather than comparing each newcomer
	 * against a handful of old ones and then against each other.
	 *
	 * <p>
	 * The rebuild is recognised by what it does to the relation table - it
	 * replaces the family's set - while an arrival only ever adds to it.
	 */
	@Test
	void aLibraryWhoseCoveredSetIsTinyComparedToWhatIsMissingIsRebuiltRatherThanIncremented() {
		alike(1, ALPHA);
		alike(2, ALPHA);

		library.service().add(MINIMUM, SILENT);

		assertThat(library.covered()).as("the slice the old cap had reached").hasSize(2);

		// Only what happens from here decides the claim: the first run had its own
		// route to take, and it is not what this test is about.
		clearInvocations(library.writer());

		alike(3, BETA);
		alike(4, BETA);
		alike(5, BETA);

		library.service().add(MINIMUM, SILENT);

		verify(library.writer(), never()).save(any(), any(), any(), any(), anyInt(), any(), any());
		verify(library.writer()).replaceAll(any(), any(), any(), any(), anyInt(), any());

		assertThat(library.covered()).as("and afterwards the whole library is incorporated").hasSize(5);
	}

	/**
	 * The ordinary arrival goes the other way: a few photos against a library that
	 * is already covered stays incremental, and the relations that were there are
	 * added to rather than recomputed.
	 */
	@Test
	void aSmallArrivalAgainstACoveredLibraryStaysIncremental() {
		alike(1, ALPHA);
		alike(2, ALPHA);
		alike(3, ALPHA);

		library.service().add(MINIMUM, SILENT);

		clearInvocations(library.writer());

		alike(4, ALPHA);

		library.service().add(MINIMUM, SILENT);

		verify(library.writer()).save(any(), any(), any(), any(), anyInt(), any(), any());
		verify(library.writer(), never()).replaceAll(any(), any(), any(), any(), anyInt(), any());
	}

	/**
	 * Both sides of the boundary from the same covered set, so what is being shown
	 * is the rule and not one lucky size: an arrival no larger than the covered set
	 * stays incremental, and one that dwarfs it rebuilds.
	 */
	@Test
	void theRouteSwapsWhenTheArrivalOutgrowsTheCoveredSet() {
		unrelated(1, 4);

		library.service().add(MINIMUM, SILENT);

		clearInvocations(library.writer());

		// Four newcomers against four covered: the two routes cost within a rounding
		// of each other, and the tie goes to the arrival.
		unrelated(5, 8);

		library.service().add(MINIMUM, SILENT);

		verify(library.writer()).save(any(), any(), any(), any(), anyInt(), any(), any());
		verify(library.writer(), never()).replaceAll(any(), any(), any(), any(), anyInt(), any());

		clearInvocations(library.writer());

		// A hundred against eight is the shape of the upgrade, and it rebuilds.
		unrelated(9, 108);

		library.service().add(MINIMUM, SILENT);

		verify(library.writer()).replaceAll(any(), any(), any(), any(), anyInt(), any());
		verify(library.writer(), never()).save(any(), any(), any(), any(), anyInt(), any(), any());
	}

	/**
	 * However the library was reached, the answer is the one a rebuild over the
	 * final set gives. The route is a cost decision and may never be a difference
	 * of result.
	 */
	@Test
	void bothRoutesReachTheSameAnswer() {
		alike(1, ALPHA);
		alike(2, ALPHA);

		library.service().add(MINIMUM, SILENT);

		alike(3, BETA);
		alike(4, BETA);
		alike(5, BETA);

		SimilarityAnalysisResult incremental = library.service().add(MINIMUM, SILENT);

		assertThat(groupsOf(incremental))
				.isEqualTo(groupsOf(library.copy().service().analyze(MINIMUM, SILENT)));
	}

	/** Photos nobody should relate: each in a hash neighbourhood of its own. */
	private void unrelated(int from, int to) {
		for (int catalogFileId = from; catalogFileId <= to; catalogFileId++) {
			library.photo(catalogFileId, PhotoSimilarityLibrary.hash(catalogFileId * 7919L, 0),
					PhotoSimilarityLibrary.sample(catalogFileId, 0));
		}
	}

	/** A photo in the shared neighbourhood, so the sample decides. */
	private void alike(int catalogFileId, long look) {
		library.photo(catalogFileId, PhotoSimilarityLibrary.hash(NEARBY, catalogFileId % 16),
				PhotoSimilarityLibrary.sample(look, 0));
	}

	private List<Set<UUID>> groupsOf(SimilarityAnalysisResult result) {
		return result.groups().stream().map(group -> group.members().stream().map(AnalyzedMember::mediaPublicId)
				.collect(Collectors.toSet())).toList();
	}

	private UUID publicId(long catalogFileId) {
		return PhotoSimilarityLibrary.publicId(catalogFileId);
	}
}