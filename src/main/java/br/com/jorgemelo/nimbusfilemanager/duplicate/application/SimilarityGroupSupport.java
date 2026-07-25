package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateCandidateFileResponse;

/**
 * Media-agnostic helpers shared by the photo and video similarity services.
 * These pieces were byte-for-byte duplicated between the two services,
 * differing only in the candidate/response type; they are parameterized by
 * accessor functions here rather than shared through an artificial common
 * supertype on the response records or candidate DTOs.
 */
final class SimilarityGroupSupport {

	private SimilarityGroupSupport() {
	}

	/**
	 * Drops candidates hidden from comparison - by their own public id or because
	 * they sit at or under an excluded folder - before the O(n²) grouping, so
	 * excluded media never surfaces in the Fotos Semelhantes tab.
	 */
	static <C> List<C> withoutExcluded(List<C> candidates, DuplicateExclusionService exclusion, Function<C, UUID> id,
			Function<C, String> folder) {
		Set<UUID> excludedIds = new HashSet<>(exclusion.excludedFilePublicIds());

		List<String> excludedFolders = exclusion.excludedFolders();

		if (excludedIds.isEmpty() && excludedFolders.isEmpty()) {
			return candidates;
		}

		return candidates.stream().filter(candidate -> !excludedIds.contains(id.apply(candidate)))
				.filter(candidate -> !exclusion.isUnderExcludedFolder(folder.apply(candidate), excludedFolders))
				.toList();
	}

	/**
	 * Whether a cached group survives a soft-delete: a group is dropped entirely
	 * when any of its members (the kept file or any delete/review candidate) was
	 * removed.
	 */
	static boolean retains(UUID keepId, List<DuplicateCandidateFileResponse> deleteCandidates,
			List<DuplicateCandidateFileResponse> reviewCandidates, Set<UUID> removed) {
		if (removed.contains(keepId)) {
			return false;
		}

		boolean removedDeleteCandidate = deleteCandidates.stream().map(DuplicateCandidateFileResponse::id)
				.anyMatch(removed::contains);

		boolean removedReviewCandidate = reviewCandidates.stream().map(DuplicateCandidateFileResponse::id)
				.anyMatch(removed::contains);

		return !removedDeleteCandidate && !removedReviewCandidate;
	}

	/**
	 * Cache signature derived from the first fingerprint-signature row
	 * ({@code count-max(id)-max(updatedAt)} shape); "empty" when there are no
	 * fingerprints, so an empty set still tags the cache deterministically.
	 */
	static String signatureOf(List<Object[]> rows) {
		if (rows.isEmpty()) {
			return "empty";
		}

		Object[] row = rows.get(0);

		return row[0] + "-" + row[1] + "-" + row[2];
	}
}