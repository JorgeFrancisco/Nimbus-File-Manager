package br.com.jorgemelo.nimbusfilemanager.execution.application.classifier;

import java.io.FileNotFoundException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;

class ExecutionErrorClassifierTest {

	private final ExecutionErrorClassifier classifier = new ExecutionErrorClassifier();

	@Test
	void shouldClassifyByRootExceptionType() {
		Assertions.assertThat(classifier.classify(new RuntimeException(new AccessDeniedException("photo.jpg"))))
				.isEqualTo(ExecutionErrorType.ACCESS_DENIED);
		Assertions.assertThat(classifier.classify(new RuntimeException(new NoSuchFileException("photo.jpg"))))
				.isEqualTo(ExecutionErrorType.FILE_NOT_FOUND);
		Assertions.assertThat(classifier.classify(new RuntimeException(new FileNotFoundException("photo.jpg"))))
				.isEqualTo(ExecutionErrorType.FILE_NOT_FOUND);
	}

	@Test
	void shouldClassifyByMessageContent() {
		Assertions.assertThat(classifier.classify(new RuntimeException("cyclic redundancy check")))
				.isEqualTo(ExecutionErrorType.CRC_ERROR);
		Assertions.assertThat(classifier.classify(new RuntimeException("hash calculation failed")))
				.isEqualTo(ExecutionErrorType.HASH_ERROR);
		Assertions.assertThat(classifier.classify(new RuntimeException("exif metadata failed")))
				.isEqualTo(ExecutionErrorType.METADATA_ERROR);
	}

	@Test
	void shouldFallbackToUnknown() {
		Assertions.assertThat(classifier.classify(new RuntimeException("unexpected")))
				.isEqualTo(ExecutionErrorType.UNKNOWN);
	}

	@Test
	void shouldClassifyEveryMessageVariant() {
		Assertions.assertThat(classifier.classify(new RuntimeException("Erro de CRC no setor")))
				.isEqualTo(ExecutionErrorType.CRC_ERROR);
		Assertions.assertThat(classifier.classify(new RuntimeException("verificação cíclica de redundância")))
				.isEqualTo(ExecutionErrorType.CRC_ERROR);
		Assertions.assertThat(classifier.classify(new RuntimeException("Access is denied")))
				.isEqualTo(ExecutionErrorType.ACCESS_DENIED);
		Assertions.assertThat(classifier.classify(new RuntimeException("acesso negado ao arquivo")))
				.isEqualTo(ExecutionErrorType.ACCESS_DENIED);
		Assertions.assertThat(classifier.classify(new RuntimeException("não é possível encontrar o arquivo")))
				.isEqualTo(ExecutionErrorType.FILE_NOT_FOUND);
		Assertions.assertThat(classifier.classify(new RuntimeException("cannot find the path specified")))
				.isEqualTo(ExecutionErrorType.FILE_NOT_FOUND);
		Assertions.assertThat(classifier.classify(new RuntimeException("The system cannot find the file")))
				.isEqualTo(ExecutionErrorType.FILE_NOT_FOUND);
		Assertions.assertThat(classifier.classify(new RuntimeException("mediainfo returned nothing")))
				.isEqualTo(ExecutionErrorType.METADATA_ERROR);
	}

	@Test
	void shouldTreatANullMessageAsUnknown() {
		Assertions.assertThat(classifier.classify(new IllegalStateException())).isEqualTo(ExecutionErrorType.UNKNOWN);
	}
}