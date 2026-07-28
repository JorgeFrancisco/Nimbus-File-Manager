package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.jorgemelo.nimbusfilemanager.execution.application.classifier.ExecutionErrorClassifier;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.model.ExecutionError;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.ExecutionErrorRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

@ExtendWith(MockitoExtension.class)
class ExecutionErrorServiceTest {

	@Mock
	private ExecutionErrorRepository executionErrorRepository;

	@Mock
	private ExecutionErrorClassifier executionErrorClassifier;

	@Test
	void saveShouldClassifyAndPersistExecutionError() {
		Execution execution = Execution.builder().id(1L).build();

		Exception exception = new IllegalArgumentException("bad file");

		when(executionErrorClassifier.classify(exception)).thenReturn(ExecutionErrorType.METADATA_ERROR);

		service().save(Path.of("C:/input/photo.jpg"), exception, execution);

		ArgumentCaptor<ExecutionError> captor = ArgumentCaptor.forClass(ExecutionError.class);

		verify(executionErrorRepository).save(captor.capture());

		Assertions.assertThat(captor.getValue().getExecution()).isSameAs(execution);
		Assertions.assertThat(captor.getValue().getErrorType()).isEqualTo(ExecutionErrorType.METADATA_ERROR);
		Assertions.assertThat(captor.getValue().getErrorMessage()).isEqualTo("bad file");
		Assertions.assertThat(captor.getValue().getPath()).endsWith("photo.jpg");

		verify(executionErrorClassifier).classify(any());
	}

	private ExecutionErrorService service() {
		return new ExecutionErrorService(executionErrorRepository, executionErrorClassifier);
	}
}