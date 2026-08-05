package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.Arrays;

/**
 * The pairs an analysis approved, in a form the clustering can consult without
 * recomputing anything.
 *
 * <p>
 * Only approvals are stored, and that is not an approximation. The grouping asks
 * one question - does this pair reach the threshold - and every way of failing it
 * gives the same answer: outside the radius, compared and rejected, or never
 * compared all collapse into "no". So the absence of a relation is a complete
 * answer, and the rejections, which outnumber the approvals by three orders of
 * magnitude, never need to exist.
 *
 * <p>
 * Laid out as compressed adjacency - one offset per node into a shared array of
 * neighbours - rather than as a map of pairs. A hash map of a million boxed keys
 * cost 68 MB when it was measured, for lookups that are answered here by a
 * binary search over a handful of neighbours; the whole structure is three int
 * arrays and no objects.
 *
 * <p>
 * Symmetric by construction: a relation is inserted from both sides, so asking
 * about (a, b) and (b, a) is the same question and neither caller has to know
 * which order the pair was discovered in.
 */
final class ApprovedRelations {

	private final int[] offsets;
	private final int[] neighbours;
	private final int[] scores;

	private ApprovedRelations(int[] offsets, int[] neighbours, int[] scores) {
		this.offsets = offsets;
		this.neighbours = neighbours;
		this.scores = scores;
	}

	/**
	 * @param first one index per approved pair
	 * @param second the other index of each pair, positionally aligned
	 * @param pairScores the score of each pair, positionally aligned
	 * @param nodes how many items exist in total, including those with no relation
	 */
	static ApprovedRelations of(int[] first, int[] second, int[] pairScores, int count, int nodes) {
		int[] offsets = new int[nodes + 1];

		for (int index = 0; index < count; index++) {
			offsets[first[index] + 1]++;
			offsets[second[index] + 1]++;
		}

		for (int node = 0; node < nodes; node++) {
			offsets[node + 1] += offsets[node];
		}

		int[] neighbours = new int[count * 2];
		int[] scores = new int[count * 2];
		int[] cursor = Arrays.copyOf(offsets, nodes);

		for (int index = 0; index < count; index++) {
			place(neighbours, scores, cursor, first[index], second[index], pairScores[index]);
			place(neighbours, scores, cursor, second[index], first[index], pairScores[index]);
		}

		sortNeighbours(offsets, neighbours, scores, nodes);

		return new ApprovedRelations(offsets, neighbours, scores);
	}

	/**
	 * The score of an approved pair, or a negative value when the pair was not
	 * approved - which is the same contract the lazy scorer had, so callers that
	 * compared against a threshold keep comparing the same way.
	 */
	int scoreOf(int node, int other) {
		int found = indexOf(node, other);

		return found < 0 ? -1 : scores[found];
	}

	boolean approved(int node, int other) {
		return indexOf(node, other) >= 0;
	}

	int degree(int node) {
		return offsets[node + 1] - offsets[node];
	}

	int neighbourAt(int node, int position) {
		return neighbours[offsets[node] + position];
	}

	/** Bytes the three arrays occupy, for the measurement to report honestly. */
	long bytes() {
		return (long) (offsets.length + neighbours.length + scores.length) * Integer.BYTES;
	}

	private int indexOf(int node, int other) {
		return Arrays.binarySearch(neighbours, offsets[node], offsets[node + 1], other);
	}

	private static void place(int[] neighbours, int[] scores, int[] cursor, int from, int to, int score) {
		neighbours[cursor[from]] = to;
		scores[cursor[from]] = score;
		cursor[from]++;
	}

	/**
	 * Each node's neighbours are sorted so lookups can bisect them. Insertion sort
	 * per node rather than a general sort: the lists are tiny - under one neighbour
	 * per photo on the measured library - and this keeps the parallel score array
	 * aligned without allocating pairs to sort.
	 */
	private static void sortNeighbours(int[] offsets, int[] neighbours, int[] scores, int nodes) {
		for (int node = 0; node < nodes; node++) {
			for (int index = offsets[node] + 1; index < offsets[node + 1]; index++) {
				int neighbour = neighbours[index];
				int score = scores[index];
				int position = index - 1;

				while (position >= offsets[node] && neighbours[position] > neighbour) {
					neighbours[position + 1] = neighbours[position];
					scores[position + 1] = scores[position];
					position--;
				}

				neighbours[position + 1] = neighbour;
				scores[position + 1] = score;
			}
		}
	}
}