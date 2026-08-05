package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

/**
 * The approved pairs in both shapes the run needs them: indexed for the
 * grouping, and flat for the writer.
 *
 * <p>
 * The grouping wants adjacency it can bisect; persistence wants three parallel
 * arrays it can batch. Deriving one from the other afterwards would mean walking
 * tens of thousands of relations a second time to recover numbers that were in
 * hand when they were computed, so both come out of the same pass.
 *
 * @param count how many entries of the arrays are filled - they grow by doubling
 * and are usually longer than the data
 */
record BuiltRelations(ApprovedRelations relations, int[] first, int[] second, int[] scores, int count) {
}