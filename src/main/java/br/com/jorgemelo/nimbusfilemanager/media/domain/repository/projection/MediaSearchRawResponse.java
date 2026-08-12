package br.com.jorgemelo.nimbusfilemanager.media.domain.repository.projection;

import java.time.Instant;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

public record MediaSearchRawResponse(

		UUID id,

		String fileName, String extension, String fileType,

		long sizeBytes,

		String currentPath, String currentFolder,

		Instant createdAt, Instant modifiedAt,

		Integer year, Integer month, Integer day, String yearMonth,

		String videoCodec, String audioCodec, Double durationSeconds,

		Integer width, Integer height,

		String manufacturer, String model) {

	public MediaSearchRawResponse(Long id, String fileName, String extension, String fileType, long sizeBytes,
			String currentPath, String currentFolder, Instant createdAt, Instant modifiedAt, Integer year,
			Integer month, Integer day, String yearMonth, String videoCodec, String audioCodec, Double durationSeconds,
			Integer width, Integer height, String manufacturer, String model) {
		this(UuidV7.fromLegacy(id), fileName, extension, fileType, sizeBytes, currentPath, currentFolder, createdAt,
				modifiedAt, year, month, day, yearMonth, videoCodec, audioCodec, durationSeconds, width, height,
				manufacturer, model);
	}
}