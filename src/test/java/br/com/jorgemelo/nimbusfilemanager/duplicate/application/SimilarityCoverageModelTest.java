package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * Whether a single boolean per file is enough to say what the stored relations
 * cover - and what it has to be a boolean <em>about</em>.
 *
 * <p>
 * The relation table stores only approvals, so it cannot tell "never compared"
 * from "compared and did not match". A run that adds files therefore needs a
 * second record saying which files are already incorporated; without it, the
 * only safe move is to compare everything against everything, which is the full
 * rebuild being avoided.
 *
 * <p>
 * <b>The question this settles is not whether the record is a boolean, but what
 * the new files are compared against.</b> Comparing them against the currently
 * <em>eligible</em> files leaves a gap that no per-file flag can close, and the
 * gap is silent: the counterexample below produces a coverage record claiming
 * two files are incorporated when the pair between them was never looked at.
 * Comparing them against the <em>covered</em> files closes it, and then one
 * boolean is provably sufficient - no intervals, no history.
 *
 * <p>
 * Everything here is a simulation over a known similarity matrix, run through
 * the production {@link SimilarityRelationGrouper}. There is no database and no
 * library: what is being checked is an argument about sets, and a fixture would
 * only make it slower to read.
 */
class SimilarityCoverageModelTest {

	private static final int SCORE = 96;

	/** Which pairs are genuinely alike, as the analysis would find them. */
	private final Set<Long> alike = new LinkedHashSet<>();

	/** The pairs some run actually looked at, whatever the verdict was. */
	private final Set<Long> evaluated = new LinkedHashSet<>();

	/** The approvals, which is all the relation table would hold. */
	private final Set<Long> approved = new LinkedHashSet<>();

	/** The files the coverage record says are incorporated. */
	private final Set<Integer> covered = new TreeSet<>();

	/**
	 * <b>The counterexample.</b> A is covered, then hidden; B arrives while A is
	 * hidden and is compared only against what is eligible, which is nothing; A
	 * comes back. Both are marked covered and the pair between them was never
	 * evaluated - so if they are near-duplicates, nothing will ever group them.
	 */
	@Test
	void comparingAgainstTheEligibleFilesLeavesAPairThatIsNeverEvaluated() {
		similar(0, 1);

		addAgainstEligible(Set.of(0), Set.of(0));

		// A is hidden, B arrives, A comes back.
		addAgainstEligible(Set.of(1), Set.of(1));

		assertThat(covered).as("both are claimed as incorporated").containsExactly(0, 1);
		assertThat(wasEvaluated(0, 1)).as("but the pair between them was never looked at").isFalse();
		assertThat(groupsOver(Set.of(0, 1))).as("so the two never group").isEmpty();
		assertThat(rebuildOver(Set.of(0, 1))).as("while a rebuild finds them at once").hasSize(1);
	}

	/** 1. A covered, B new: the pair is evaluated and the answer matches a rebuild. */
	@Test
	void aCoveredFileAndANewOne() {
		similar(0, 1);

		addAgainstCovered(Set.of(0));
		addAgainstCovered(Set.of(1));

		assertEquivalent(Set.of(0, 1));
	}

	/** 2. Both new in the same batch: the internal pairs of the batch count too. */
	@Test
	void twoNewFilesInTheSameBatch() {
		similar(0, 1);

		addAgainstCovered(Set.of(0, 1));

		assertEquivalent(Set.of(0, 1));
	}

	/**
	 * 3. A is covered and matched nothing, then B arrives and matches A. The case
	 * the relation table alone cannot express: A leaves no row, so without the
	 * coverage record A would look like a file nobody had ever compared.
	 */
	@Test
	void aCoveredFileWithNoApprovalsStillCountsAsIncorporated() {
		addAgainstCovered(Set.of(0));

		assertThat(approved).as("A matched nothing and left no relation").isEmpty();
		assertThat(covered).as("but it is incorporated all the same").containsExactly(0);

		similar(0, 1);

		addAgainstCovered(Set.of(1));

		assertEquivalent(Set.of(0, 1));
	}

	/** 4. A hidden, B arrives, A returns - the counterexample, now closed. */
	@Test
	void aFileHiddenWhileAnotherArrivesIsStillComparedAgainstIt() {
		similar(0, 1);

		addAgainstCovered(Set.of(0));

		// A is hidden here. It stays covered, so B is compared against it anyway.
		addAgainstCovered(Set.of(1));

		assertThat(wasEvaluated(0, 1)).as("the pair was looked at while A was hidden").isTrue();

		// A returns: nothing to recompute, because nothing was skipped.
		assertEquivalent(Set.of(0, 1));
	}

	/** 5. Two files hidden at different moments, both returning later. */
	@Test
	void twoFilesHiddenAtDifferentMomentsAndBothReturning() {
		similar(0, 2);
		similar(1, 2);

		addAgainstCovered(Set.of(0));
		addAgainstCovered(Set.of(1));
		addAgainstCovered(Set.of(2));

		assertEquivalent(Set.of(0, 1, 2));
	}

	/**
	 * 6. The fingerprint of A changes while its catalog id does not. Its relations
	 * and its coverage are both forgotten, so it re-enters as new and is compared
	 * again - against everything covered, not only against what arrived since.
	 */
	@Test
	void aChangedFingerprintReentersAsNew() {
		similar(0, 1);

		addAgainstCovered(Set.of(0, 1));

		forget(0);

		assertThat(covered).as("A is no longer incorporated").containsExactly(1);
		assertThat(approved).as("and the relations computed from its old image are gone").isEmpty();

		// The new image of A happens to look like nothing.
		alike.clear();

		addAgainstCovered(Set.of(0));

		assertEquivalent(Set.of(0, 1));
	}

	/**
	 * 7. A crash between writing the relations and marking the coverage. The two
	 * belong to one transaction, so the state cannot exist - and if it did, the
	 * file would simply still be new and the retry would be idempotent.
	 */
	@Test
	void aRunThatWroteRelationsButNeverMarkedCoverageIsSafeToRepeat() {
		similar(0, 1);

		addAgainstCovered(Set.of(0));

		// The relations of the batch land; the coverage does not.
		evaluatePairs(Set.of(1), covered);
		evaluatePairs(Set.of(1), Set.of(1));

		int approvedAfterCrash = approved.size();

		addAgainstCovered(Set.of(1));

		assertThat(approved).as("repeating adds nothing new").hasSize(approvedAfterCrash);
		assertEquivalent(Set.of(0, 1));
	}

	/** 8. Half a batch landed. The rest is still new, and the retry finishes it. */
	@Test
	void aBatchThatOnlyPartlyLandedIsFinishedByTheNextRun() {
		similar(0, 1);
		similar(1, 2);
		similar(0, 2);

		addAgainstCovered(Set.of(0));
		addAgainstCovered(Set.of(1));

		assertThat(covered).containsExactly(0, 1);

		addAgainstCovered(Set.of(2));

		assertEquivalent(Set.of(0, 1, 2));
	}

	/**
	 * 9. Two runs starting from the same state and adding the same file. The
	 * second is a no-op rather than a duplicate: relations are upserted by their
	 * key and coverage is inserted once.
	 */
	@Test
	void twoRunsAddingTheSameFileLeaveTheSameState() {
		similar(0, 1);

		addAgainstCovered(Set.of(0));

		addAgainstCovered(Set.of(1));

		int relations = approved.size();

		addAgainstCovered(Set.of(1));

		assertThat(approved).hasSize(relations);
		assertThat(covered).containsExactly(0, 1);
		assertEquivalent(Set.of(0, 1));
	}

	/**
	 * And the whole argument at once, over many shapes: files arriving in batches,
	 * hidden and restored along the way, must end up grouped exactly as a rebuild
	 * over the final set would group them.
	 */
	@Test
	void anyInterleavingOfArrivalsAndHidingMatchesARebuild() {
		for (int seed = 0; seed < 40; seed++) {
			reset();

			Random random = new Random(seed);
			int files = 12;

			for (int left = 0; left < files; left++) {
				for (int right = left + 1; right < files; right++) {
					if (random.nextInt(100) < 25) {
						similar(left, right);
					}
				}
			}

			for (int file = 0; file < files; file++) {
				addAgainstCovered(Set.of(file));
			}

			Set<Integer> everything = new TreeSet<>();

			for (int file = 0; file < files; file++) {
				everything.add(file);
			}

			assertThat(groupsOver(everything)).as("seed %d", seed).isEqualTo(rebuildOver(everything));
			assertThat(everyCoveredPairWasEvaluated()).as("seed %d: no pair was skipped", seed).isTrue();
		}
	}

	/**
	 * The rule this class exists to justify: new files are compared against every
	 * file already incorporated - not against the ones that happen to be eligible
	 * right now - and against each other. Coverage is granted only afterwards.
	 */
	private void addAgainstCovered(Set<Integer> newcomers) {
		evaluatePairs(newcomers, covered);
		evaluatePairs(newcomers, newcomers);

		covered.addAll(newcomers);
	}

	/** The rule that does not work, kept so the counterexample can run. */
	private void addAgainstEligible(Set<Integer> newcomers, Set<Integer> eligible) {
		Set<Integer> others = new TreeSet<>(eligible);

		others.removeAll(newcomers);

		evaluatePairs(newcomers, others);
		evaluatePairs(newcomers, newcomers);

		covered.addAll(newcomers);
	}

	private void evaluatePairs(Set<Integer> newcomers, Set<Integer> against) {
		for (int newcomer : newcomers) {
			for (int other : against) {
				if (newcomer == other) {
					continue;
				}

				evaluated.add(key(newcomer, other));

				if (alike.contains(key(newcomer, other))) {
					approved.add(key(newcomer, other));
				}
			}
		}
	}

	/** A re-fingerprint: the file's relations and its coverage both go. */
	private void forget(int file) {
		approved.removeIf(pair -> touches(pair, file));
		evaluated.removeIf(pair -> touches(pair, file));

		covered.remove(file);
	}

	private void assertEquivalent(Set<Integer> eligible) {
		assertThat(everyCoveredPairWasEvaluated()).as("no pair between incorporated files was skipped").isTrue();
		assertThat(groupsOver(eligible)).isEqualTo(rebuildOver(eligible));
	}

	private boolean everyCoveredPairWasEvaluated() {
		List<Integer> files = new ArrayList<>(covered);

		for (int left = 0; left < files.size(); left++) {
			for (int right = left + 1; right < files.size(); right++) {
				if (!wasEvaluated(files.get(left), files.get(right))) {
					return false;
				}
			}
		}

		return true;
	}

	/** The groups the product would publish from what the relation table holds. */
	private List<List<Integer>> groupsOver(Set<Integer> eligible) {
		return group(eligible, approved);
	}

	/** The groups a full rebuild would find, comparing everything again. */
	private List<List<Integer>> rebuildOver(Set<Integer> eligible) {
		return group(eligible, alike);
	}

	private List<List<Integer>> group(Set<Integer> eligible, Set<Long> pairs) {
		List<Integer> nodes = new ArrayList<>(new TreeSet<>(eligible));

		int[] first = new int[nodes.size() * nodes.size()];
		int[] second = new int[first.length];
		int[] scores = new int[first.length];
		int count = 0;

		for (int left = 0; left < nodes.size(); left++) {
			for (int right = left + 1; right < nodes.size(); right++) {
				if (pairs.contains(key(nodes.get(left), nodes.get(right)))) {
					first[count] = left;
					second[count] = right;
					scores[count] = SCORE;
					count++;
				}
			}
		}

		List<List<Integer>> clusters = SimilarityRelationGrouper
				.cluster(nodes.size(), ApprovedRelations.of(first, second, scores, count, nodes.size()), (_, _) -> {
				});

		return clusters.stream().map(cluster -> cluster.stream().map(nodes::get).toList()).toList();
	}

	private void reset() {
		alike.clear();
		evaluated.clear();
		approved.clear();
		covered.clear();
	}

	private void similar(int left, int right) {
		alike.add(key(left, right));
	}

	private boolean wasEvaluated(int left, int right) {
		return evaluated.contains(key(left, right));
	}

	private boolean touches(long pair, int file) {
		return (int) (pair >>> 32) == file || (int) pair == file;
	}

	private long key(int left, int right) {
		return ((long) Math.min(left, right) << 32) | Math.max(left, right);
	}
}