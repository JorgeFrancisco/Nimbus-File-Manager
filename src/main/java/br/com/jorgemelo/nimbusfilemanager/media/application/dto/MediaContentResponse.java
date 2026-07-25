package br.com.jorgemelo.nimbusfilemanager.media.application.dto;

import java.nio.file.Path;

public record MediaContentResponse(Path file, long totalLength, ByteRange byteRange, String fileName,
		String contentType) {
}