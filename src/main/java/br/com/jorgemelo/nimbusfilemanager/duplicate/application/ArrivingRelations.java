package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

/**
 * What an arrival produced, and what it cost to produce it.
 *
 * <p>
 * The relations and the two id arrays travel together because the writer needs
 * all three and none of them can be derived from the others: the relations name
 * positions, {@code catalogFileIds} says which file each position is, and
 * {@code newlyCovered} says which of those files this run may now claim as
 * incorporated - a subset, because a file only becomes covered by having been
 * compared against everything that already was.
 *
 * @param candidatePairs how many pairs survived the distance filter, which is
 * the number of SSIM comparisons the run was asked for
 * @param samplesLoaded how many luminance samples had to be read - the figure
 * the two-phase load exists to keep small, and therefore the one worth saying
 * out loud
 */
record ArrivingRelations(BuiltRelations relations, long[] catalogFileIds, long[] newlyCovered, int candidatePairs,
		int samplesLoaded) {
}