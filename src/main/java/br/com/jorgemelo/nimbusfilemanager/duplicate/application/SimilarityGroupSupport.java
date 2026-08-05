package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedMember;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.CompositionRow;
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
	 * The canonical composition of an analysis: which files entered it, in which
	 * order, each with the folder that decides whether it belongs.
	 *
	 * <p>
	 * This is the one place the subset is derived, and both sides call it. The
	 * application calls it before queueing, over a light projection that carries
	 * only ids and folders; the worker calls it over the rows it is about to
	 * analyse, hashes and luminance included. Same rows in, same list out - so the
	 * two agree by construction rather than by two implementations somebody hopes
	 * are equivalent.
	 *
	 * <p>
	 * The consecutive de-duplication is what makes it right for video. There, the
	 * candidate cap counts <em>frame rows</em>, not videos, and the rows arrive
	 * ordered by file and sample; a video sitting on the cut enters with the frames
	 * that fit and no others. Collapsing by id while preserving order reproduces
	 * exactly that, including the partial one - which is the case a "limit the
	 * number of videos" shortcut would silently get wrong.
	 *
	 * @param rows already ordered and already capped by the query, exactly as the
	 * analysis loads them
	 */
	static <R> List<CompositionRow> canonicalComposition(List<R> rows, Function<R, UUID> id,
			Function<R, String> folder, DuplicateExclusionService exclusion) {
		List<CompositionRow> entries = new ArrayList<>();

		UUID previous = null;

		for (R row : rows) {
			UUID current = id.apply(row);

			if (!current.equals(previous)) {
				entries.add(new CompositionRow(current, folder.apply(row)));

				previous = current;
			}
		}

		return withoutExcluded(entries, exclusion, CompositionRow::mediaPublicId, CompositionRow::currentFolder);
	}

	/**
	 * The durable shape of one analysed group: which files, what was decided, and
	 * in which order - the keep first, then the delete candidates, then the ones
	 * left for review.
	 *
	 * <p>
	 * Everything else the response carries (names, paths, sizes, thumbnails) is
	 * catalog data read again when the screen renders, so a file renamed after
	 * publication shows its current name instead of a frozen copy.
	 */
	static AnalyzedGroup toAnalyzedGroup(int similarityPercent, long wastedBytes,
			DuplicateCandidateFileResponse keep, List<DuplicateCandidateFileResponse> deleteCandidates,
			List<DuplicateCandidateFileResponse> reviewCandidates) {
		List<AnalyzedMember> members = new ArrayList<>();

		members.add(toMember(keep));
		deleteCandidates.forEach(file -> members.add(toMember(file)));
		reviewCandidates.forEach(file -> members.add(toMember(file)));

		return new AnalyzedGroup(similarityPercent, wastedBytes, List.copyOf(members));
	}

	private static AnalyzedMember toMember(DuplicateCandidateFileResponse file) {
		return new AnalyzedMember(file.id(), file.verdict(), file.reason());
	}
}