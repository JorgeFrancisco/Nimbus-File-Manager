package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.nio.file.Path;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.execution.application.classifier.ExecutionErrorClassifier;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.model.ExecutionError;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.ExecutionErrorRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

@Service
public class ExecutionErrorService {

	private final ExecutionErrorRepository executionErrorRepository;
	private final ExecutionErrorClassifier executionErrorClassifier;

	public ExecutionErrorService(ExecutionErrorRepository executionErrorRepository,
			ExecutionErrorClassifier executionErrorClassifier) {
		this.executionErrorRepository = executionErrorRepository;
		this.executionErrorClassifier = executionErrorClassifier;
	}

	public void save(Path file, Exception exception, Execution execution) {
		save(file, executionErrorClassifier.classify(exception), exception.getMessage(), execution);
	}

	/**
	 * Records a failure whose reason is already known, for the work that does not
	 * fail with an exception: a conversion refused by validation, a move the file
	 * system would not complete. Without this the execution screen counted those
	 * failures and could not say which file they belonged to.
	 */
	public void save(Path file, ExecutionErrorType errorType, String errorMessage, Execution execution) {
		ExecutionError error = ExecutionError.builder().execution(execution)
				.path(file.toAbsolutePath().normalize().toString()).errorType(errorType)
				.errorMessage(errorMessage).build();

		executionErrorRepository.save(error);
	}
}