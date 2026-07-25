package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

import java.util.List;

/**
 * Keep / delete-candidate / review-candidate views of a group plus recoverable
 * bytes.
 */
public record GroupParts(DuplicateCandidateFileResponse keep, List<DuplicateCandidateFileResponse> deleteCandidates,
		List<DuplicateCandidateFileResponse> reviewCandidates, long wastedBytes) {
}