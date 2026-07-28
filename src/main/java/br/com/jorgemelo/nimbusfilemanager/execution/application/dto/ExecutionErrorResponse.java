package br.com.jorgemelo.nimbusfilemanager.execution.application.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

public record ExecutionErrorResponse(UUID id, UUID executionId, String path, String errorType, String errorMessage,
		LocalDateTime createdAt) {

	public ExecutionErrorResponse(Long id, Long executionId, String path, String errorType, String errorMessage,
			LocalDateTime createdAt) {
		this(UuidV7.fromLegacy(id), executionId == null ? null : UuidV7.fromLegacy(executionId), path, errorType,
				errorMessage, createdAt);
	}
}