package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.SizeResponse;

/**
 * A group of visually similar videos. {@code similarityPercent} is the
 * guaranteed floor for the group - the lowest pairwise similarity found between
 * any two videos in it, so it is never more optimistic than reality.
 */
public record SimilarVideoGroupResponse(String groupId, long files, int similarityPercent, SizeResponse wastedSize,
		DuplicateCandidateFileResponse keep, List<DuplicateCandidateFileResponse> deleteCandidates,
		List<DuplicateCandidateFileResponse> reviewCandidates) {

	public SimilarVideoGroupResponse(String groupId, long files, int similarityPercent, SizeResponse wastedSize,
			DuplicateCandidateFileResponse keep, List<DuplicateCandidateFileResponse> deleteCandidates) {
		this(groupId, files, similarityPercent, wastedSize, keep, deleteCandidates, List.of());
	}
}