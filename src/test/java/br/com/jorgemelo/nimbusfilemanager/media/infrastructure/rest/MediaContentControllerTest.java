package br.com.jorgemelo.nimbusfilemanager.media.infrastructure.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;

import br.com.jorgemelo.nimbusfilemanager.media.application.MediaContentService;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.MediaContentSource;
import br.com.jorgemelo.nimbusfilemanager.media.infrastructure.persistence.MediaContentRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;

class MediaContentControllerTest {

	private final MediaContentRepository repository = mock(MediaContentRepository.class);

	private final MediaContentService contentService = new MediaContentService(repository);

	private final MediaContentController controller = new MediaContentController(contentService);

	@TempDir
	Path temp;

	@Test
	void shouldStreamOnlyRequestedByteRange() throws Exception {
		UUID id = UUID.randomUUID();

		Path video = temp.resolve("clip.mp4");
		Files.write(video, new byte[] { 0, 1, 2, 3, 4, 5, 6, 7 });

		when(repository.findContent(id)).thenReturn(
				Optional.of(new MediaContentSource(id, video.toString(), "clip.mp4", "video/mp4", FileType.VIDEO)));

		MockHttpServletResponse response = new MockHttpServletResponse();

		controller.content(id, "bytes=2-5", response);

		Assertions.assertThat(response.getStatus()).isEqualTo(HttpStatus.PARTIAL_CONTENT.value());
		Assertions.assertThat(response.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 2-5/8");
		Assertions.assertThat(response.getHeader(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
		Assertions.assertThat(response.getContentAsByteArray()).containsExactly(2, 3, 4, 5);
	}

	@Test
	void shouldReturnNotFoundWithoutExposingAPath() throws Exception {
		UUID id = UUID.randomUUID();

		when(repository.findContent(id)).thenReturn(Optional.empty());

		MockHttpServletResponse response = new MockHttpServletResponse();

		controller.content(id, null, response);

		Assertions.assertThat(response.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
	}
}