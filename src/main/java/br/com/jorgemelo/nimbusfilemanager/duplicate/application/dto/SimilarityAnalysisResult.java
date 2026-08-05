package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

import java.util.List;

/**
 * What one analysis produced: the groups, and the honest account of what it was
 * about.
 *
 * <p>
 * The composition travels with the groups rather than being reconstructed
 * afterwards, because only the analysis knows it - how many files were eligible
 * before the cap, how many the cap let through, and the digest of exactly those.
 * Asking the database again after the fact would answer about the library as it
 * is now, which is a different set from the one that was analysed.
 */
public record SimilarityAnalysisResult(SimilarityFamily family, SimilarityComposition composition,
		List<AnalyzedGroup> groups) {

	public int memberCount() {
		return groups.stream().mapToInt(group -> group.members().size()).sum();
	}
}