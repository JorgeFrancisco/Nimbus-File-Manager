package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Reason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Verdict;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.SizeResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

/**
 * File member of a duplicate/similarity group with the definitive decision
 * produced by the domain policy. The view layer must consume these values and
 * must not recalculate the decision.
 */
public record DuplicateCandidateFileResponse(UUID id, String fileName, String extension, String fileType,
		SizeResponse size, String currentPath, String currentFolder, Instant modifiedAt, Verdict verdict,
		Reason reason, Integer width, Integer height, LocalDateTime captureDate, DateSource dateSource) {

	public DuplicateCandidateFileResponse(UUID id, String fileName, String extension, String fileType,
			SizeResponse size, String currentPath, String currentFolder, Instant modifiedAt) {
		this(id, fileName, extension, fileType, size, currentPath, currentFolder, modifiedAt, null, null, null, null,
				null, null);
	}

	public DuplicateCandidateFileResponse(UUID id, String fileName, String extension, String fileType,
			SizeResponse size, String currentPath, String currentFolder, Instant modifiedAt, Verdict verdict,
			Reason reason) {
		this(id, fileName, extension, fileType, size, currentPath, currentFolder, modifiedAt, verdict, reason, null,
				null, null, null);
	}

	public DuplicateCandidateFileResponse(Long id, String fileName, String extension, String fileType,
			SizeResponse size, String currentPath, String currentFolder, Instant modifiedAt) {
		this(UuidV7.fromLegacy(id), fileName, extension, fileType, size, currentPath, currentFolder, modifiedAt, null,
				null, null, null, null, null);
	}

	public DuplicateCandidateFileResponse(Long id, String fileName, String extension, String fileType,
			SizeResponse size, String currentPath, String currentFolder, Instant modifiedAt, Verdict verdict,
			Reason reason) {
		this(UuidV7.fromLegacy(id), fileName, extension, fileType, size, currentPath, currentFolder, modifiedAt,
				verdict, reason, null, null, null, null);
	}
}