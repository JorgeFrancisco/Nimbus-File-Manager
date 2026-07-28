package br.com.jorgemelo.nimbusfilemanager.execution.application.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

public record MovementResponse(UUID id, UUID executionId, UUID catalogFileId, String sourcePath, String targetPath,
		String status, String reason, LocalDateTime movedAt, LocalDateTime undoneAt) {

	public MovementResponse(Long id, Long executionId, Long catalogFileId, String sourcePath, String targetPath,
			String status, String reason, LocalDateTime movedAt, LocalDateTime undoneAt) {
		this(UuidV7.fromLegacy(id), UuidV7.fromLegacy(executionId),
				catalogFileId == null ? null : UuidV7.fromLegacy(catalogFileId), sourcePath, targetPath, status, reason,
				movedAt, undoneAt);
	}
}