package br.com.jorgemelo.nimbusfilemanager.metadata.application.facade;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataOptions;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.extractor.MetadataExtractor;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.model.MetadataResult;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ExecutionMetricsContext;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ProcessingMetrics;

@ExtendWith(MockitoExtension.class)
class MetadataFacadeTest {

	/** This test's own accumulator: nothing here is shared with another run. */
	private final ProcessingMetrics metrics = new ExecutionMetricsContext().processing();

	@TempDir
	Path tempDir;

	@Mock
	private MetadataExtractor metadataExtractor;

	@Test
	void extractShouldValidateFileAndDelegateToExtractor() throws Exception {
		Path file = Files.writeString(tempDir.resolve("photo.jpg"), "content");

		MetadataOptions options = new MetadataOptions(true, false);

		MetadataResult expected = MetadataResult.builder().build();

		when(metadataExtractor.extract(file, options, metrics)).thenReturn(expected);

		MetadataResult result = new MetadataFacade(metadataExtractor).extract(file, options, metrics);

		Assertions.assertThat(result).isSameAs(expected);
	}

	@Test
	void extractShouldRejectInvalidFileBeforeDelegating() {
		assertThatIllegalArgumentException().isThrownBy(
				() -> new MetadataFacade(metadataExtractor).extract(tempDir.resolve("missing.jpg"), null, metrics))
				.withMessageContaining("File does not exist");
	}
}