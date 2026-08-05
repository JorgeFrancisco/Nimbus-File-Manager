package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.RelationParameters;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.SimilarityRelationRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.RelationRow;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence.SimilarityRelationWriter;

/**
 * The two relation tables, in memory, behaving the way the database behaves.
 *
 * <p>
 * Shared by the photo and the video harness because the tables are shared and
 * their semantics are not about the medium: what a family is, what an upsert
 * does to a key that is already there, what coverage claims, and what forgetting
 * an algorithm leaves behind are the same statements whichever fingerprint
 * produced the pair. A second copy of this for videos would be a second
 * definition of coverage, and the day one of them drifted the tests would still
 * pass.
 *
 * <p>
 * Keyed by the four values a relation is identified by, in the order the
 * database indexes them, so a prefix of the key is a family and the natural
 * ordering of the keys is the ordering the queries promise.
 */
final class RelationStore {

	/** Approved relations, keyed so that the natural order is the stored order. */
	private final TreeMap<String, Integer> relations = new TreeMap<>();

	private final TreeSet<String> coverage = new TreeSet<>();

	private final SimilarityRelationRepository repository = mock(SimilarityRelationRepository.class);
	private final SimilarityRelationWriter writer = mock(SimilarityRelationWriter.class);

	RelationStore() {
		wireReads();
		wireWrites();
	}

	SimilarityRelationRepository repository() {
		return repository;
	}

	/**
	 * The writer, so a test can ask <em>which</em> route a run took: a rebuild
	 * replaces the family's relations, an arrival adds to them, and the two are
	 * different methods.
	 */
	SimilarityRelationWriter writer() {
		return writer;
	}

	int relationCount() {
		return relations.size();
	}

	Set<Long> covered() {
		Set<Long> covered = new TreeSet<>();

		for (String key : coverage) {
			covered.add(catalogFileId(key));
		}

		return covered;
	}

	/** Every pair the relation table holds, as "smaller-larger", for assertions. */
	Set<String> approvedPairs() {
		Set<String> pairs = new TreeSet<>();

		for (String key : relations.keySet()) {
			pairs.add(first(key) + "-" + second(key));
		}

		return pairs;
	}

	/** The score stored for a pair under any family, for assertions on scores. */
	List<Integer> scoresOf(long left, long right) {
		List<Integer> scores = new ArrayList<>();

		for (Map.Entry<String, Integer> entry : relations.entrySet()) {
			if (first(entry.getKey()) == Math.min(left, right) && second(entry.getKey()) == Math.max(left, right)) {
				scores.add(entry.getValue());
			}
		}

		return scores;
	}

	/** The catalog row is deleted for good, and the foreign keys cascade. */
	void purge(long catalogFileId) {
		relations.keySet().removeIf(key -> touches(key, catalogFileId));
		coverage.removeIf(key -> catalogFileId(key) == catalogFileId);
	}

	private void wireReads() {
		when(repository.findEligibleRelations(any(), anyInt(), anyInt(), any(), any())).thenAnswer(call -> {
			String prefix = familyPrefix(call.getArgument(0), call.getArgument(1), call.getArgument(2),
					call.getArgument(3));

			Set<Long> wanted = Set.of((Long[]) call.getArgument(4));

			List<RelationRow> rows = new ArrayList<>();

			for (Map.Entry<String, Integer> entry : relations.entrySet()) {
				if (entry.getKey().startsWith(prefix) && wanted.contains(first(entry.getKey()))
						&& wanted.contains(second(entry.getKey()))) {
					rows.add(StoredRelationRow.approved(first(entry.getKey()), second(entry.getKey()),
							entry.getValue()));
				}
			}

			return rows;
		});

		when(repository.findEligibleNotCovered(any(), anyInt(), anyInt(), any(), any())).thenAnswer(call -> {
			String prefix = familyPrefix(call.getArgument(0), call.getArgument(1), call.getArgument(2),
					call.getArgument(3));

			return Arrays.stream(call.<Long[]>getArgument(4)).sorted()
					.filter(id -> !coverage.contains(prefix + padded(id))).toList();
		});

		when(repository.findCovered(any(), anyInt(), anyInt(), any())).thenAnswer(call -> {
			String prefix = familyPrefix(call.getArgument(0), call.getArgument(1), call.getArgument(2),
					call.getArgument(3));

			return coverage.stream().filter(key -> key.startsWith(prefix)).map(this::catalogFileId).sorted().toList();
		});

		when(repository.findAnalysedThresholds(any(), anyInt(), any())).thenAnswer(call -> {
			String prefix = call.getArgument(0) + "|" + padded(call.<Integer>getArgument(1)) + "|";
			String digest = call.getArgument(2);

			return coverage.stream().filter(key -> key.startsWith(prefix))
					.filter(key -> key.split("\\|")[3].equals(digest))
					.map(key -> Integer.parseInt(key.split("\\|")[2])).distinct().sorted().toList();
		});
	}

	private void wireWrites() {
		when(writer.replaceAll(any(), any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
			RelationParameters parameters = call.getArgument(0);

			String prefix = familyPrefix(parameters);

			relations.keySet().removeIf(key -> key.startsWith(prefix));
			coverage.removeIf(key -> key.startsWith(prefix));

			long[] catalogFileIds = call.getArgument(5);

			upsert(parameters, call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4),
					catalogFileIds);

			cover(parameters, catalogFileIds);

			return call.<Integer>getArgument(4);
		});

		when(writer.save(any(), any(), any(), any(), anyInt(), any(), any())).thenAnswer(call -> {
			RelationParameters parameters = call.getArgument(0);

			upsert(parameters, call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4),
					call.getArgument(5));

			cover(parameters, call.getArgument(6));

			return call.<Integer>getArgument(4);
		});

		// Every argument after the first: a varargs call arrives here already expanded,
		// so asking for one argument would forget one file and ignore the rest. The
		// first is the algorithm, and honouring it is what keeps a video's
		// invalidation from taking the photo relations of the same catalog row.
		when(writer.forget(any(), any(Long[].class))).thenAnswer(call -> {
			Object[] arguments = call.getArguments();

			String prefix = arguments[0] + "|";

			int forgotten = 0;

			for (int index = 1; index < arguments.length; index++) {
				long catalogFileId = (Long) arguments[index];

				forgotten += relations.keySet().stream()
						.filter(key -> key.startsWith(prefix) && touches(key, catalogFileId)).toList().size();

				relations.keySet().removeIf(key -> key.startsWith(prefix) && touches(key, catalogFileId));
				coverage.removeIf(key -> key.startsWith(prefix) && catalogFileId(key) == catalogFileId);
			}

			return forgotten;
		});

	}

	private void upsert(RelationParameters parameters, int[] first, int[] second, int[] scores, int count,
			long[] catalogFileIds) {
		for (int index = 0; index < count; index++) {
			relations.put(relationKey(parameters, catalogFileIds[first[index]], catalogFileIds[second[index]]),
					scores[index]);
		}
	}

	private void cover(RelationParameters parameters, long[] catalogFileIds) {
		for (long catalogFileId : catalogFileIds) {
			coverage.add(familyPrefix(parameters) + padded(catalogFileId));
		}
	}

	private boolean touches(String key, long catalogFileId) {
		return first(key) == catalogFileId || second(key) == catalogFileId;
	}

	private String relationKey(RelationParameters parameters, long left, long right) {
		return familyPrefix(parameters) + padded(Math.min(left, right)) + '|' + padded(Math.max(left, right));
	}

	private String familyPrefix(RelationParameters parameters) {
		return familyPrefix(parameters.algorithmId(), parameters.maxDistance(), parameters.minSimilarity(),
				parameters.relationDigest());
	}

	private String familyPrefix(String algorithm, int maxDistance, int minSimilarity, String relationDigest) {
		return algorithm + '|' + padded(maxDistance) + '|' + padded(minSimilarity) + '|' + relationDigest + '|';
	}

	/**
	 * Zero-padded so that the natural ordering of the keys is the ordering the
	 * queries promise - by catalog id, and by the pair.
	 */
	private String padded(long value) {
		return String.format("%019d", value);
	}

	private long catalogFileId(String key) {
		return Long.parseLong(key.substring(key.lastIndexOf('|') + 1));
	}

	private long first(String key) {
		return Long.parseLong(key.split("\\|")[4]);
	}

	private long second(String key) {
		return Long.parseLong(key.split("\\|")[5]);
	}
}