package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;

public record Signals(UUID id, boolean hasCameraExif, MediaSubcategory subcategory, Integer width, Integer height,
		DateSource dateSource, LocalDateTime captureDate, Instant modifiedAt) {
}