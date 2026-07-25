package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MimeTypeServiceTest {

	@TempDir
	Path tempDir;

	private final MimeTypeService service = new MimeTypeService();

	@Test
	void shouldDetectTheMimeType() throws Exception {
		Path text = Files.writeString(tempDir.resolve("file.txt"), "hello");

		Assertions.assertThat(service.detect(text)).startsWith("text/");
	}

	@Test
	void shouldRejectInvalidPath() {
		assertThatIllegalArgumentException().isThrownBy(() -> service.detect(null))
				.withMessage("File path must not be null.");
		assertThatIllegalArgumentException().isThrownBy(() -> service.detect(tempDir.resolve("missing.txt")))
				.withMessageContaining("File does not exist");
		assertThatIllegalArgumentException().isThrownBy(() -> service.detect(tempDir))
				.withMessageContaining("Path is not a regular file");
	}

	@Test
	void shouldFallbackToDefaultMimeTypeWhenDetectorReturnsBlankOrThrows() throws Exception {
		Path file = Files.writeString(tempDir.resolve("file.bin"), "content");
		MimeTypeService nullMime = new MimeTypeService(_ -> null);
		MimeTypeService blank = new MimeTypeService(_ -> " ");
		MimeTypeService failure = new MimeTypeService(_ -> {
			throw new IOException("bad");
		});

		Assertions.assertThat(nullMime.detect(file)).isEqualTo("application/octet-stream");
		Assertions.assertThat(blank.detect(file)).isEqualTo("application/octet-stream");
		Assertions.assertThat(failure.detect(file)).isEqualTo("application/octet-stream");
	}
}