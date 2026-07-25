package br.com.jorgemelo.nimbusfilemanager.media.application.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;

public record MediaDetails(UUID id, String fileName, FileType type, LocalDateTime captureDate, DateSource dateSource,
		LocalDateTime createdAt, LocalDateTime modifiedAt, Integer width, Integer height, String manufacturer,
		String model, Double latitude, Double longitude, Double durationSeconds, String currentPath, String contentUrl,
		String location, Double locationDistanceKm, String locationConfidence, String locationConfidenceLevel,
		String locationSource, String typeLabel, String dateSourceLabel) {
}