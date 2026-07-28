package br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.projection;

public record ExecutionErrorSummaryResponse(String errorType, long count) {
}