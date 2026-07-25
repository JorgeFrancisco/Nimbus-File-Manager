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
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.MediaDetails;
import br.com.jorgemelo.nimbusfilemanager.media.infrastructure.persistence.MediaContentRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;

/**
 * Complements {@link MediaContentControllerTest}: details lookup, full-file
 * streaming, missing-file 404, multi-range fallback, probed content type and
 * unsatisfiable-range rejection.
 */
class MediaContentControllerExtraTest {

	private final MediaContentRepository repository = mock(MediaContentRepository.class);

	private final MediaContentService contentService = new MediaContentService(repository);

	private final MediaContentController controller = new MediaContentController(contentService);

	private final UUID id = UUID.fromString("01890000-0000-7000-8000-000000000001");

	@TempDir
	Path dir;

	@Test
	void detailsReturnsFoundThenNotFound() {
		MediaDetails details = new MediaDetails(id, "f.jpg", FileType.PHOTO, null, null, null, null, null, null, null,
				null, null, null, null, null, null, null, null, null, null, null, null, null);

		when(repository.findDetails(id)).thenReturn(Optional.of(details));

		Assertions.assertThat(controller.details(id).getStatusCode()).isEqualTo(HttpStatus.OK);

		when(repository.findDetails(id)).thenReturn(Optional.empty());

		Assertions.assertThat(controller.details(id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void contentReturnsNotFoundWhenFileIsMissing() throws Exception {
		when(repository.findContent(id)).thenReturn(Optional.of(source(dir.resolve("gone.mp4"), "video/mp4")));

		MockHttpServletResponse response = new MockHttpServletResponse();

		controller.content(id, null, response);

		Assertions.assertThat(response.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	@Test
	void contentStreamsWholeFileWhenNoRangeRequested() throws Exception {
		Path file = Files.writeString(dir.resolve("clip.txt"), "hello world");

		when(repository.findContent(id)).thenReturn(Optional.of(source(file, "text/plain")));

		MockHttpServletResponse response = new MockHttpServletResponse();

		controller.content(id, null, response);

		Assertions.assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
		Assertions.assertThat(response.getHeader(HttpHeaders.CONTENT_LENGTH)).isEqualTo("11");
		Assertions.assertThat(response.getContentAsString()).isEqualTo("hello world");
	}

	@Test
	void contentTreatsMultiRangeAsFullDownload() throws Exception {
		Path file = Files.writeString(dir.resolve("clip.txt"), "hello world");

		when(repository.findContent(id)).thenReturn(Optional.of(source(file, "text/plain")));

		MockHttpServletResponse response = new MockHttpServletResponse();

		controller.content(id, "bytes=0-2,5-7", response);

		Assertions.assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
		Assertions.assertThat(response.getContentAsByteArray()).hasSize(11);
	}

	@Test
	void contentProbesContentTypeWhenMimeIsBlank() throws Exception {
		Path file = Files.writeString(dir.resolve("clip.txt"), "hello world");

		when(repository.findContent(id)).thenReturn(Optional.of(source(file, "  ")));

		MockHttpServletResponse response = new MockHttpServletResponse();

		controller.content(id, null, response);

		Assertions.assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
	}

	@Test
	void contentRejectsUnsatisfiableRange() throws Exception {
		Path file = Files.writeString(dir.resolve("clip.txt"), "hello world");

		when(repository.findContent(id)).thenReturn(Optional.of(source(file, "text/plain")));

		MockHttpServletResponse response = new MockHttpServletResponse();

		Assertions.assertThatThrownBy(() -> controller.content(id, "bytes=100-200", response))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private MediaContentSource source(Path path, String mimeType) {
		return new MediaContentSource(id, path.toString(), path.getFileName().toString(), mimeType, FileType.VIDEO);
	}
}