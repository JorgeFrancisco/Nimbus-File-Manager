package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

import java.util.List;

/**
 * A published similarity group as the public API returns it.
 *
 * @param outdated whether the library changed since this analysis. It is
 * information, not a warning to act on: the result is still a true statement
 * about the files it examined
 * @param members every file the analysis grouped, each saying whether it can
 * still be acted upon - a consumer must not offer a deletion for one that
 * cannot
 */
public record SimilarityGroupResponse(String groupId, int similarityPercent, long wastedBytes, boolean outdated,
		List<SimilarityMemberResponse> members) {
}