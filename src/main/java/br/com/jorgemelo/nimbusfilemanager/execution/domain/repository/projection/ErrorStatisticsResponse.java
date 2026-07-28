package br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.projection;

public record ErrorStatisticsResponse(String errorType, long count) {
}