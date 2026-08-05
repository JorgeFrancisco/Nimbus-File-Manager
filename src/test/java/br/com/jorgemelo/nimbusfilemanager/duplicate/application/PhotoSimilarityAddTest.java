package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedMember;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisResult;

/**
 * Incorporating what arrived, through the production path, checked against the
 * full rebuild of the same library.
 *
 * <p>
 * Every test here ends the same way: the groups the arrivals produced are
 * compared with the groups a rebuild produces over the final set of files. That
 * comparison is the point. An expectation written by hand would say only that
 * the test and the code were written together; the rebuild is the definition of
 * the right answer, and it is available, so it is what the arrivals are held to.
 *
 * <p>
 * The data is synthetic and the pipeline is not. Distances, SSIM, the greedy
 * complete-linkage placement and the coverage bookkeeping are the real classes -
 * what is simulated is the database, in {@link PhotoSimilarityLibrary}. Photos are
 * built so that the two filters can be aimed separately: a hash decides whether
 * a pair is even looked at, a luminance sample decides whether it is approved.
 */
class PhotoSimilarityAddTest {

	private static final int MINIMUM = 70;

	/**
	 * One hash neighbourhood for everything, so that every pair is a candidate and
	 * what separates the photos is the sample. Tests that need a pair the distance
	 * filter rejects say so by asking for a different neighbourhood.
	 */
	private static final long NEARBY = 7;

	private static final long ALPHA = 101;
	private static final long BETA = 202;
	private static final long GAMMA = 303;

	private static final SimilarityProgressCallback SILENT = (_, _) -> {
	};

	private final PhotoSimilarityLibrary library = new PhotoSimilarityLibrary();

	/**
	 * The two filters do what the fixture claims they do. Stated first because
	 * every test below is written in terms of it: same look means approved,
	 * different look means rejected, and a photo hashed elsewhere is never even
	 * compared.
	 */
	@Test
	void theFixtureSeparatesTheTwoFiltersItClaimsTo() {
		photo(1, ALPHA);
		photo(2, ALPHA);
		photo(3, BETA);

		library.photo(4, PhotoSimilarityLibrary.hash(999, 0), PhotoSimilarityLibrary.sample(ALPHA, 0));

		add();

		assertThat(library.approvedPairs()).as("same look approved, different look rejected, far hash never asked")
				.containsExactly("1-2");
	}

	/** 1. One new photo, related to nothing. No group appears and none changes. */
	@Test
	void aNewPhotoRelatedToNothingChangesNothing() {
		photo(1, ALPHA);
		photo(2, ALPHA);

		add();

		photo(3, BETA);

		assertMatchesRebuild(add());
	}

	/**
	 * 2. The new photo pairs with one that was alone. The case a published result
	 * cannot answer by itself - groups of one are never stored - and the case that
	 * makes coverage necessary: the lone file left no relation row either.
	 */
	@Test
	void aNewPhotoPairingWithAFileThatWasAloneFormsANewGroup() {
		photo(1, ALPHA);

		add();

		assertThat(library.relationCount()).as("a file that matched nothing leaves no row").isZero();
		assertThat(library.covered()).as("but it is incorporated all the same").containsExactly(1L);

		photo(2, ALPHA);

		SimilarityAnalysisResult result = add();

		assertThat(groupsOf(result)).containsExactly(Set.of(publicId(1), publicId(2)));
		assertMatchesRebuild(result);
	}

	/** 3. The new photo relates to every member of a group, so it joins. */
	@Test
	void aNewPhotoRelatedToEveryMemberJoinsTheGroup() {
		photo(1, ALPHA);
		photo(2, ALPHA);

		add();

		photo(3, ALPHA);

		SimilarityAnalysisResult result = add();

		assertThat(groupsOf(result)).containsExactly(Set.of(publicId(1), publicId(2), publicId(3)));
		assertMatchesRebuild(result);
	}

	/**
	 * 4. Related to a member but not to all of them: complete linkage refuses, and
	 * the newcomer starts a group of its own rather than weakening the existing
	 * one.
	 *
	 * <p>
	 * The middle photo is three quarters of the way from one look to the other, so
	 * it clears the threshold against the first and the last clears it against the
	 * middle - while the two ends do not. That non-transitive shape is the whole
	 * point, and no amount of brightness shifting produces it.
	 */
	@Test
	void aNewPhotoWithRelationsInsufficientForCompleteLinkageStartsItsOwnGroup() {
		byte[] alpha = PhotoSimilarityLibrary.sample(ALPHA, 0);
		byte[] beta = PhotoSimilarityLibrary.sample(BETA, 0);

		look(1, alpha);
		look(2, PhotoSimilarityLibrary.blended(alpha, beta, 0.75));

		add();

		assertThat(groupsOf(regroup())).as("the two ends of the chain that exist so far").hasSize(1);

		look(3, PhotoSimilarityLibrary.blended(alpha, beta, 0.5));

		SimilarityAnalysisResult result = add();

		assertThat(library.approvedPairs()).as("the newcomer relates to the middle but not to the far end")
				.containsExactly("1-2", "2-3");
		assertThat(groupsOf(result)).as("so it cannot join, and a group of one is not published")
				.containsExactly(Set.of(publicId(1), publicId(2)));
		assertMatchesRebuild(result);
	}

	/**
	 * 5. The newcomer fits two groups. It joins the one created first, which is
	 * what the greedy placement does and what a rebuild would do.
	 */
	@Test
	void aNewPhotoCompatibleWithMoreThanOneGroupJoinsTheEarliest() {
		byte[] alpha = PhotoSimilarityLibrary.sample(ALPHA, 0);
		byte[] beta = PhotoSimilarityLibrary.sample(BETA, 0);

		look(1, alpha);
		look(2, alpha);
		look(3, beta);
		look(4, beta);

		add();

		assertThat(groupsOf(regroup())).hasSize(2);

		// Between the two looks and close enough to both, which is what makes it fit
		// either group.
		look(5, PhotoSimilarityLibrary.blended(alpha, beta, 0.5));

		SimilarityAnalysisResult result = add();

		assertMatchesRebuild(result);
	}

	/** 6. Several newcomers at once, related to each other and to nothing else. */
	@Test
	void severalNewPhotosRelatedToEachOtherFormTheirOwnGroup() {
		photo(1, ALPHA);

		add();

		photo(2, BETA);
		photo(3, BETA);
		photo(4, BETA);

		SimilarityAnalysisResult result = add();

		assertThat(groupsOf(result)).containsExactly(Set.of(publicId(2), publicId(3), publicId(4)));
		assertMatchesRebuild(result);
	}

	/** 7. Several newcomers, each relating to a different covered file. */
	@Test
	void severalNewPhotosRelatedToTheCoveredOnes() {
		photo(1, ALPHA);
		photo(2, BETA);
		photo(3, GAMMA);

		add();

		photo(4, ALPHA);
		photo(5, BETA);

		SimilarityAnalysisResult result = add();

		assertThat(groupsOf(result)).containsExactlyInAnyOrder(Set.of(publicId(1), publicId(4)),
				Set.of(publicId(2), publicId(5)));
		assertMatchesRebuild(result);
	}

	/**
	 * 8. A newcomer whose catalog id is lower than the files already covered.
	 *
	 * <p>
	 * The ordinary case is the opposite - an arriving photo gets the highest id -
	 * and the equivalence argument leans on it. This is the case that does not:
	 * a file catalogued long ago becomes eligible today because its fingerprint
	 * was finally computed. It has to be placed where its id puts it, not where it
	 * arrived, which is why the comparison is ordered by catalog id and not by
	 * arrival.
	 */
	@Test
	void aNewPhotoWithALowerCatalogIdIsPlacedWhereItsIdPutsItAndNotWhereItArrived() {
		byte[] alpha = PhotoSimilarityLibrary.sample(ALPHA, 0);
		byte[] beta = PhotoSimilarityLibrary.sample(BETA, 0);

		look(2, alpha);
		look(3, PhotoSimilarityLibrary.blended(alpha, beta, 0.75));

		add();

		look(1, PhotoSimilarityLibrary.blended(alpha, beta, 0.5));

		assertMatchesRebuild(add());
	}

	/**
	 * 9. <b>The counterexample the coverage model exists for.</b> A file that is
	 * covered but hidden today - excluded, quarantined or logically deleted - is
	 * still compared against the newcomer. Comparing against the eligible files
	 * instead would leave that pair evaluated by nobody, and once both are covered
	 * no later run would ever look at it again.
	 */
	@Test
	void aCoveredFileThatIsHiddenIsStillComparedAgainstTheNewcomer() {
		photo(1, ALPHA);

		add();

		library.hide(1);

		photo(2, ALPHA);

		add();

		assertThat(library.approvedPairs()).as("the pair was evaluated while the file was hidden")
				.containsExactly("1-2");

		library.show(1);

		// Nothing to recompute: the relation was already there when the file came back.
		SimilarityAnalysisResult result = regroup();

		assertThat(groupsOf(result)).containsExactly(Set.of(publicId(1), publicId(2)));
		assertMatchesRebuild(result);
	}

	/**
	 * 10. And the return itself costs nothing. The file was never re-analysed, and
	 * the answer after it comes back is the rebuild's answer.
	 */
	@Test
	void aFileReturningToEligibilityIsNotRecomputed() {
		photo(1, ALPHA);
		photo(2, ALPHA);

		add();

		library.hide(2);

		assertThat(groupsOf(regroup())).as("hidden, so no group").isEmpty();

		library.show(2);

		int relations = library.relationCount();

		SimilarityAnalysisResult result = add();

		assertThat(library.relationCount()).as("nothing was recomputed").isEqualTo(relations);
		assertMatchesRebuild(result);
	}

	/**
	 * 11. A photo fingerprinted again is not an arrival: its relations and its
	 * coverage are forgotten first, so it re-enters as a file nobody has compared
	 * and is measured against the whole covered set - not only against what has
	 * arrived since.
	 */
	@Test
	void aReFingerprintedPhotoIsComparedAgainstEverythingAndLeavesNoGhostRelation() {
		photo(1, ALPHA);
		photo(2, ALPHA);
		photo(3, BETA);

		add();

		assertThat(library.approvedPairs()).containsExactly("1-2");

		// The same catalog id, a different image - and one that now looks like the
		// third photo instead of the first.
		library.refingerprint(2, PhotoSimilarityLibrary.hash(NEARBY, 2), PhotoSimilarityLibrary.sample(BETA, 0));

		assertThat(library.approvedPairs()).as("what was computed from the old image is gone").isEmpty();
		assertThat(library.covered()).as("and the file is new again").containsExactly(1L, 3L);

		SimilarityAnalysisResult result = add();

		assertThat(library.approvedPairs()).as("the relation it has now, and no ghost of the one it had")
				.containsExactly("2-3");
		assertMatchesRebuild(result);
	}

	/** 12. Running the same arrival twice adds nothing and changes nothing. */
	@Test
	void repeatingAnAddLeavesTheSameState() {
		photo(1, ALPHA);
		photo(2, ALPHA);

		add();

		photo(3, ALPHA);

		add();

		int relations = library.relationCount();
		Set<Long> covered = library.covered();

		SimilarityAnalysisResult repeated = add();

		assertThat(library.relationCount()).isEqualTo(relations);
		assertThat(library.covered()).isEqualTo(covered);
		assertMatchesRebuild(repeated);
	}

	/**
	 * 13. An arrival that finds nothing new still answers. It is the ordinary
	 * outcome of asking twice, and refusing to publish would leave whoever asked
	 * with no result and no reason.
	 */
	@Test
	void anAddThatFindsNothingNewStillProducesTheAnswer() {
		photo(1, ALPHA);
		photo(2, ALPHA);

		add();

		SimilarityAnalysisResult result = add();

		assertThat(groupsOf(result)).containsExactly(Set.of(publicId(1), publicId(2)));
		assertThat(result.composition().analyzedCount()).isEqualTo(2);
		assertMatchesRebuild(result);
	}

	/**
	 * 14. A file deleted for good takes its relations and its coverage with it -
	 * the cascade the migration declares - and what is left is still the rebuild's
	 * answer.
	 */
	@Test
	void aPurgedFileLeavesNothingBehind() {
		photo(1, ALPHA);
		photo(2, ALPHA);
		photo(3, ALPHA);

		add();

		library.purge(2);

		assertThat(library.covered()).containsExactly(1L, 3L);

		assertMatchesRebuild(add());
	}

	/**
	 * 15. Stopped part way through, wherever that happens to be - inside the
	 * distance scan, inside SSIM, after the relations were written, inside the
	 * grouping. In every case the next run finishes the job and reaches the
	 * rebuild's answer.
	 *
	 * <p>
	 * <b>Why relations written before a cancel may stay.</b> They are facts about
	 * pairs of images and remain true whether or not anybody published a grouping.
	 * What makes keeping them safe is not that claim on its own, though - it is
	 * that each one is written in the same transaction as the coverage that
	 * accounts for it, so the state after a cancel is never "this file is
	 * incorporated" without the pairs behind it. A file whose coverage did not land
	 * is simply still new, and the next run compares it again and upserts the same
	 * rows over themselves.
	 *
	 * <p>
	 * The stop points are counted in progress callbacks because that is exactly
	 * what the production cancellation is: {@code SimilarityJob} checks for a
	 * cancel inside the callback the analysis reports through, and throws from
	 * there.
	 */
	@ParameterizedTest
	@ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
	void aCancelAtAnyPointLeavesAStateTheNextRunFinishes(int callbacksBeforeStopping) {
		photo(1, ALPHA);
		photo(2, ALPHA);

		add();

		photo(3, ALPHA);
		photo(4, BETA);

		PhotoSimilarityService service = library.service();

		SimilarityProgressCallback cancelling = stopAfter(callbacksBeforeStopping);

		assertThatThrownBy(() -> service.add(MINIMUM, cancelling))
				.as("the cancellation unwinds through the callback, as it does in production")
				.isInstanceOf(IllegalStateException.class);

		assertMatchesRebuild(add());
	}

	/**
	 * A callback that behaves like the one the job installs: it reports, and at
	 * some point it notices a cancellation and throws out of the analysis.
	 */
	private SimilarityProgressCallback stopAfter(int callbacks) {
		int[] seen = { 0 };

		return (_, _) -> {
			if (seen[0]++ >= callbacks) {
				throw new IllegalStateException("cancelled");
			}
		};
	}

	private void photo(long catalogFileId, long look) {
		look(catalogFileId, PhotoSimilarityLibrary.sample(look, 0));
	}

	/**
	 * A photo in the shared hash neighbourhood with the sample it is given, so the
	 * pair is always a candidate and the sample alone decides.
	 */
	private void look(long catalogFileId, byte[] sample) {
		library.photo(catalogFileId, PhotoSimilarityLibrary.hash(NEARBY, (int) catalogFileId), sample);
	}

	private SimilarityAnalysisResult add() {
		return library.service().add(MINIMUM, SILENT);
	}

	private SimilarityAnalysisResult regroup() {
		return library.service().regroup(MINIMUM, SILENT);
	}

	/**
	 * The claim, made the same way every time: what the arrivals produced is what
	 * a full rebuild over the final set produces.
	 */
	private void assertMatchesRebuild(SimilarityAnalysisResult incremental) {
		SimilarityAnalysisResult rebuilt = library.copy().service().analyze(MINIMUM, SILENT);

		assertThat(groupsOf(incremental)).as("the arrivals reached the rebuild's answer")
				.isEqualTo(groupsOf(rebuilt));
	}

	private List<Set<UUID>> groupsOf(SimilarityAnalysisResult result) {
		return result.groups().stream().map(group -> group.members().stream().map(AnalyzedMember::mediaPublicId)
				.collect(Collectors.toSet())).toList();
	}

	private UUID publicId(long catalogFileId) {
		return PhotoSimilarityLibrary.publicId(catalogFileId);
	}
}