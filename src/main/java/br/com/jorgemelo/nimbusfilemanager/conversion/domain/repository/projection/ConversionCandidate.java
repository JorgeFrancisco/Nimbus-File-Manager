package br.com.jorgemelo.nimbusfilemanager.conversion.domain.repository.projection;

import java.util.UUID;

/**
 * A convertible video as the catalog knows it: everything the Conversão screen
 * needs to describe the file, with no entity graph to walk afterwards.
 */
public record ConversionCandidate(UUID publicId, String fileName, String currentPath, String currentFolder,
		Long sizeBytes, String extension, String videoCodec, Double durationSeconds, Integer width, Integer height) {
}