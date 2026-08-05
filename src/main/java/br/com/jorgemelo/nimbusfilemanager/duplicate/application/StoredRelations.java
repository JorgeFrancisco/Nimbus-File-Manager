package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.RelationRow;

/**
 * Approved relations read back from the database, turned into the structure the
 * grouping consults.
 *
 * <p>
 * Two things have to be recovered, and the order they are recovered in is the
 * whole care this class takes. A stored relation names two catalog ids; the
 * grouping works on positions, and which position a file gets decides the
 * answer, because the placement is greedy and visits candidates in
 * {@code catalog_file.id} order.
 *
 * <p>
 * So the files are named first - {@link #participants()} - the caller loads
 * exactly those, and only then are the relations indexed against the list the
 * caller ended up with. Deriving the positions here instead would assume the
 * two lists match, and a single file missing from the load would shift every
 * position after it: the grouping would still run, still produce groups, and
 * quietly group the wrong files.
 *
 * <p>
 * Files that take part in no relation are not named at all, and leaving them
 * out changes nothing. A file with no approved neighbour can only ever form a
 * group of one, which is not a result, and it can never be the reason another
 * candidate was refused - so the clusters of more than one member come out
 * identical whether it is visited or not. Skipping them is what makes a regroup
 * proportional to the relations rather than to the library.
 */
final class StoredRelations {

	private final List<RelationRow> rows;
	private final List<Long> participants;

	private StoredRelations(List<RelationRow> rows, List<Long> participants) {
		this.rows = rows;
		this.participants = participants;
	}

	static StoredRelations of(List<RelationRow> rows) {
		return new StoredRelations(rows, participants(rows));
	}

	/**
	 * The catalog ids that take part in at least one relation, ascending - which
	 * is the order the analysis visits candidates in.
	 */
	List<Long> participants() {
		return participants;
	}

	/**
	 * The relations as adjacency over {@code nodes}, which must be ascending.
	 *
	 * <p>
	 * A relation whose file is absent from {@code nodes} is dropped rather than
	 * being an error: the caller filters what it loaded - a file hidden from
	 * comparison between the request and the run - and a relation is only usable
	 * when both of its files are in the analysis.
	 *
	 * @param nodes the catalog id of each candidate, at the position the grouping
	 * will refer to it by
	 */
	ApprovedRelations indexedBy(long[] nodes) {
		int[] first = new int[rows.size()];
		int[] second = new int[rows.size()];
		int[] scores = new int[rows.size()];
		int found = 0;

		for (RelationRow row : rows) {
			int left = Arrays.binarySearch(nodes, row.getFirstCatalogFileId());
			int right = Arrays.binarySearch(nodes, row.getSecondCatalogFileId());

			if (left >= 0 && right >= 0) {
				first[found] = left;
				second[found] = right;
				scores[found] = row.getSimilarityPercent();
				found++;
			}
		}

		return ApprovedRelations.of(first, second, scores, found, nodes.length);
	}

	/**
	 * Sorted and de-duplicated over a flat array rather than collected into a set:
	 * a library's worth of relations is tens of thousands of rows, and every id
	 * appears twice, so a boxed set would allocate an object per endpoint to
	 * answer a question two passes over primitives answer.
	 */
	private static List<Long> participants(List<RelationRow> rows) {
		long[] ids = new long[rows.size() * 2];
		int index = 0;

		for (RelationRow row : rows) {
			ids[index++] = row.getFirstCatalogFileId();
			ids[index++] = row.getSecondCatalogFileId();
		}

		Arrays.sort(ids);

		List<Long> distinct = new ArrayList<>();

		for (int position = 0; position < ids.length; position++) {
			if (position == 0 || ids[position] != ids[position - 1]) {
				distinct.add(ids[position]);
			}
		}

		return distinct;
	}
}