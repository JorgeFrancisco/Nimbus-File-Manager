package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The same complete-linkage decision as {@link SimilarityCompleteLinkageGrouper},
 * reached without asking the same question twice.
 *
 * <p>
 * The original walks every cluster for every candidate and calls the scorer to
 * find out whether they belong together - which, on a whole library, rebuilds
 * most of the all-pairs comparison inside the grouping loop: 6,37 billion calls
 * for 7,18 billion possible pairs, and 351 seconds to place 119.813 photos.
 *
 * <p>
 * This one starts from the observation that makes those calls unnecessary. A
 * candidate can only join a cluster if it scores against <em>every</em> member,
 * so every member must be one of its approved neighbours - and a cluster holding
 * no neighbour of the candidate can be skipped without being examined. Counting
 * how many members of each cluster are neighbours therefore decides membership
 * outright: the cluster fits exactly when that count equals its size.
 *
 * <p>
 * <b>The result is identical, not merely similar.</b> Candidates are visited in
 * the same order, and among the clusters that fit, the one created first is
 * chosen - which is what walking the list in order and stopping at the first
 * match does. The greediness and its order-dependence are preserved on purpose:
 * this is a change of structure, and mixing it with a change of behaviour would
 * make both impossible to judge.
 */
final class SimilarityRelationGrouper {

	private SimilarityRelationGrouper() {
	}

	/**
	 * @param nodes how many candidates exist, indexed 0..nodes-1 in the order they
	 * are to be visited
	 * @param relations the pairs already approved, symmetric
	 * @return clusters of more than one member, each holding indices in the order
	 * they joined
	 */
	static List<List<Integer>> cluster(int nodes, ApprovedRelations relations, SimilarityProgressCallback progress) {
		List<List<Integer>> clusters = new ArrayList<>();
		int[] clusterOf = new int[nodes];

		java.util.Arrays.fill(clusterOf, -1);

		progress.update(0, nodes);

		for (int candidate = 0; candidate < nodes; candidate++) {
			int target = firstClusterThatFits(candidate, relations, clusters, clusterOf);

			if (target < 0) {
				target = clusters.size();

				clusters.add(new ArrayList<>());
			}

			clusters.get(target).add(candidate);
			clusterOf[candidate] = target;

			progress.update(candidate + 1, nodes);
		}

		return clusters.stream().filter(cluster -> cluster.size() > 1).toList();
	}

	/**
	 * The earliest cluster whose every member is an approved neighbour of the
	 * candidate. Only clusters holding at least one neighbour are considered,
	 * because the rest cannot possibly qualify - which is the whole saving.
	 */
	private static int firstClusterThatFits(int candidate, ApprovedRelations relations, List<List<Integer>> clusters,
			int[] clusterOf) {
		Map<Integer, Integer> neighboursPerCluster = new HashMap<>();

		for (int position = 0; position < relations.degree(candidate); position++) {
			int cluster = clusterOf[relations.neighbourAt(candidate, position)];

			if (cluster >= 0) {
				neighboursPerCluster.merge(cluster, 1, Integer::sum);
			}
		}

		int earliest = -1;

		for (Map.Entry<Integer, Integer> entry : neighboursPerCluster.entrySet()) {
			boolean everyMemberIsANeighbour = entry.getValue() == clusters.get(entry.getKey()).size();

			if (everyMemberIsANeighbour && (earliest < 0 || entry.getKey() < earliest)) {
				earliest = entry.getKey();
			}
		}

		return earliest;
	}
}