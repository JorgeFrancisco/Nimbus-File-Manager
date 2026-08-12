package br.com.jorgemelo.nimbusfilemanager.execution.application.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

public record ExecutionResponse(UUID executionId, String executionType, String status, String phase,
		LocalDateTime startedAt,
		LocalDateTime finishedAt, String sourcePath, String targetPath, Integer filesFound, Integer filesAnalyzed,
		Integer cacheHits, Integer filesMoved, Integer simulatedFiles, Integer errors, Integer totalExpected,
		Double percentComplete, Integer currentItemPercent, EtaEstimate eta, String message,
		Boolean executeFlag, String statusLabel,
		Boolean finished, String typeLabel, String triggerLabel, String startedAtLabel, String finishedAtLabel) {

	public ExecutionResponse(UUID executionId, String executionType, String status, LocalDateTime startedAt,
			LocalDateTime finishedAt, String sourcePath, String targetPath, Integer filesFound, Integer filesAnalyzed,
			Integer cacheHits, Integer filesMoved, Integer simulatedFiles, Integer errors, Integer totalExpected,
			Double percentComplete, String message, Boolean executeFlag) {
		this(executionId, executionType, status, null, startedAt, finishedAt, sourcePath, targetPath, filesFound,
				filesAnalyzed, cacheHits, filesMoved, simulatedFiles, errors, totalExpected, percentComplete, null,
				EtaEstimate.notApplicable(), message, executeFlag, null, null, null, null, null, null);
	}

	public ExecutionResponse(Long executionId, String executionType, String status, LocalDateTime startedAt,
			LocalDateTime finishedAt, String sourcePath, String targetPath, Integer filesFound, Integer filesAnalyzed,
			Integer cacheHits, Integer filesMoved, Integer simulatedFiles, Integer errors, Integer totalExpected,
			Double percentComplete, String message, Boolean executeFlag) {
		this(UuidV7.fromLegacy(executionId), executionType, status, null, startedAt, finishedAt, sourcePath, targetPath,
				filesFound, filesAnalyzed, cacheHits, filesMoved, simulatedFiles, errors, totalExpected,
				percentComplete, null, EtaEstimate.notApplicable(), message, executeFlag, null, null, null, null, null,
				null);
	}
}