package br.com.jorgemelo.nimbusfilemanager.metadata.application.extractor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.ExifMetadataService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.MediaInfoService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.PhotoMetadata;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoMetadata;

class MediaMetadataReaderTest {

	private final ExifMetadataService exifToolService = mock(ExifMetadataService.class);
	private final MediaInfoService mediaInfoService = mock(MediaInfoService.class);
	private final MediaMetadataReader reader = new MediaMetadataReader(exifToolService, mediaInfoService);

	@Test
	void photoShouldDelegateToTheExifMetadataService() {
		Path file = Path.of("C:/media/photo.jpg");
		PhotoMetadata photo = new PhotoMetadata(4000, 3000, null, null, 1, null, null, null, null, null, null, null,
				null, null, null, null, null, null, null);

		when(exifToolService.extract(file)).thenReturn(photo);

		Assertions.assertThat(reader.photo(file)).isSameAs(photo);
	}

	@Test
	void videoShouldDelegateToTheMediaInfoService() {
		Path file = Path.of("C:/media/video.mp4");
		VideoMetadata video = new VideoMetadata("mov", "h265", "aac", "main", 1920, 1080, 59.94, 1000L, 1200L, 10.5, 0,
				false, "yuv420p", "bt2020", "smpte2084", "bt2020", 10, 48000, 2, "stereo", null, null, null, "{media}",
				null);

		when(mediaInfoService.extract(file)).thenReturn(video);

		Assertions.assertThat(reader.video(file)).isSameAs(video);
	}
}