package br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection;

import java.time.LocalDateTime;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;

public record TimelineItemProjection(Long internalId, UUID publicId, String fileName, FileType fileType,
		LocalDateTime captureDate, DateSource dateSource, Integer width, Integer height, Double durationSeconds,
		String location) {

	public TimelineItemProjection(Long internalId, UUID publicId, String fileName, FileType fileType,
			LocalDateTime captureDate, DateSource dateSource, Integer width, Integer height, Double durationSeconds) {
		this(internalId, publicId, fileName, fileType, captureDate, dateSource, width, height, durationSeconds, null);
	}
}