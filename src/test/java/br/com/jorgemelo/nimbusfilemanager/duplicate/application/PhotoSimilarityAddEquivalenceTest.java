package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedMember;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisResult;

/**
 * The claim the incremental path exists to make, over shapes nobody chose.
 *
 * <p>
 * {@code PhotoSimilarityAddTest} pins the named cases - a newcomer joining a
 * group, one refused by complete linkage, a covered file hidden while another
 * arrives. This one asks whether the claim survives arbitrary interleavings of
 * the same events: photos arriving in batches of random size, files hidden and
 * restored in between, and photos fingerprinted again half way through.
 *
 * <p>
 * The answer each run is held to is not written down anywhere. It is computed by
 * running the <em>full rebuild</em> over the final library, through the same
 * production classes, and the two must be equal - which is the only formulation
 * that cannot be satisfied by a test and a bug agreeing with each other.
 *
 * <p>
 * Deterministic and fast: fixed seeds, no database, no photographs. A run that
 * needed either would be a benchmark pretending to be a proof.
 */
class PhotoSimilarityAddEquivalenceTest {

	private static final int MINIMUM = 70;

	private static final int SEEDS = 30;
	private static final int FILES = 18;

	/**
	 * How many distinct appearances the photos are drawn from. Small enough that
	 * groups form and complete linkage has something to refuse, large enough that
	 * the library is not one enormous group.
	 */
	private static final int LOOKS = 5;

	private static final long NEARBY = 7;

	private static final SimilarityProgressCallback SILENT = (_, _) -> {
	};

	/**
	 * Arrivals in batches, with files hidden and restored along the way, end up
	 * grouped exactly as a rebuild over the final library groups them.
	 */
	@Test
	void anyInterleavingOfArrivalsAndHidingMatchesARebuild() {
		for (int seed = 0; seed < SEEDS; seed++) {
			PhotoSimilarityLibrary library = new PhotoSimilarityLibrary();

			Random random = new Random(seed);

			List<Long> arrived = new ArrayList<>();

			for (long catalogFileId = 1; catalogFileId <= FILES; catalogFileId++) {
				library.photo(catalogFileId, PhotoSimilarityLibrary.hash(NEARBY, (int) catalogFileId),
						PhotoSimilarityLibrary.sample(random.nextInt(LOOKS), 0));

				arrived.add(catalogFileId);

				if (random.nextInt(100) < 40) {
					hideSome(library, arrived, random);

					library.service().add(MINIMUM, SILENT);

					showAll(library, arrived);
				}
			}

			library.service().add(MINIMUM, SILENT);

			assertMatchesRebuild(library, seed);
		}
	}

	/**
	 * The same, with photos fingerprinted again in the middle. A new image under
	 * an old catalog id is the one event that can make a stored relation false, so
	 * an equivalence that survives it is an equivalence about the whole design and
	 * not only about growth.
	 */
	@Test
	void reFingerprintingInTheMiddleStillMatchesARebuild() {
		for (int seed = 0; seed < SEEDS; seed++) {
			PhotoSimilarityLibrary library = new PhotoSimilarityLibrary();

			Random random = new Random(seed * 7L + 1);

			for (long catalogFileId = 1; catalogFileId <= FILES; catalogFileId++) {
				library.photo(catalogFileId, PhotoSimilarityLibrary.hash(NEARBY, (int) catalogFileId),
						PhotoSimilarityLibrary.sample(random.nextInt(LOOKS), 0));

				if (random.nextInt(100) < 35) {
					library.service().add(MINIMUM, SILENT);
				}

				if (catalogFileId > 1 && random.nextInt(100) < 25) {
					long edited = 1 + random.nextInt((int) catalogFileId);

					library.refingerprint(edited, PhotoSimilarityLibrary.hash(NEARBY, (int) edited),
							PhotoSimilarityLibrary.sample(random.nextInt(LOOKS), 0));
				}
			}

			library.service().add(MINIMUM, SILENT);

			assertMatchesRebuild(library, seed);
		}
	}

	/**
	 * One photo at a time, which is the worst case for an incremental design and
	 * the one a phone backup produces: every arrival is its own run, and every run
	 * has to leave the library in the state a rebuild would have left it in.
	 */
	@Test
	void oneArrivalPerRunMatchesARebuildAtEveryStep() {
		PhotoSimilarityLibrary library = new PhotoSimilarityLibrary();

		Random random = new Random(4242);

		for (long catalogFileId = 1; catalogFileId <= FILES; catalogFileId++) {
			library.photo(catalogFileId, PhotoSimilarityLibrary.hash(NEARBY, (int) catalogFileId),
					PhotoSimilarityLibrary.sample(random.nextInt(LOOKS), 0));

			library.service().add(MINIMUM, SILENT);

			assertMatchesRebuild(library, (int) catalogFileId);
		}
	}

	/**
	 * Files hidden while others arrive - and the pair between a hidden file and
	 * the newcomer still has to be evaluated, which is what comparing against the
	 * covered set rather than the eligible one buys.
	 */
	private void hideSome(PhotoSimilarityLibrary library, List<Long> arrived, Random random) {
		for (long catalogFileId : arrived) {
			if (random.nextInt(100) < 30) {
				library.hide(catalogFileId);
			}
		}
	}

	private void showAll(PhotoSimilarityLibrary library, List<Long> arrived) {
		for (long catalogFileId : arrived) {
			library.show(catalogFileId);
		}
	}

	private void assertMatchesRebuild(PhotoSimilarityLibrary library, int seed) {
		List<Set<UUID>> incremental = groupsOf(library.service().regroup(MINIMUM, SILENT));

		List<Set<UUID>> rebuilt = groupsOf(library.copy().service().analyze(MINIMUM, SILENT));

		assertThat(incremental).as("seed %d", seed).isEqualTo(rebuilt);
	}

	private List<Set<UUID>> groupsOf(SimilarityAnalysisResult result) {
		return result.groups().stream().map(group -> group.members().stream().map(AnalyzedMember::mediaPublicId)
				.collect(Collectors.toSet())).toList();
	}
}