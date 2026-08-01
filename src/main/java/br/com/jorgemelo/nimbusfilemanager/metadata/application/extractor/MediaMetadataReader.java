package br.com.jorgemelo.nimbusfilemanager.metadata.application.extractor;

import java.nio.file.Path;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.ExifMetadataService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.MediaInfoService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.PhotoMetadata;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoMetadata;

/**
 * Reads embedded metadata from a media file, grouping the two readers
 * ({@link ExifMetadataService} for photos, {@link MediaInfoService} for videos)
 * behind a single collaborator so {@link MetadataExtractor} stays within the
 * constructor-parameter limit.
 */
@Service
public class MediaMetadataReader {

	private final ExifMetadataService exifMetadataService;
	private final MediaInfoService mediaInfoService;

	public MediaMetadataReader(ExifMetadataService exifMetadataService, MediaInfoService mediaInfoService) {
		this.exifMetadataService = exifMetadataService;
		this.mediaInfoService = mediaInfoService;
	}

	public PhotoMetadata photo(Path file) {
		return exifMetadataService.extract(file);
	}

	public VideoMetadata video(Path file) {
		return mediaInfoService.extract(file);
	}
}