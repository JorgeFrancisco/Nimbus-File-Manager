package br.com.jorgemelo.nimbusfilemanager.media.application.dto;

import java.time.Instant;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.SizeResponse;

public record MediaSearchResponse(

		UUID id,

		String fileName, String extension, String fileType,

		SizeResponse size,

		String currentPath, String currentFolder,

		Instant createdAt, Instant modifiedAt,

		Integer year, Integer month, Integer day, String yearMonth,

		String videoCodec, String audioCodec, Double durationSeconds,

		Integer width, Integer height,

		String manufacturer, String model) {
}