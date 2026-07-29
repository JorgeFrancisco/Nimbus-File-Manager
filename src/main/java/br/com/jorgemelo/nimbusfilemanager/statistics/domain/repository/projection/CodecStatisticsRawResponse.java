package br.com.jorgemelo.nimbusfilemanager.statistics.domain.repository.projection;

public record CodecStatisticsRawResponse(String codec, long files, long missing, double percentage,
		long totalSizeBytes) {
}