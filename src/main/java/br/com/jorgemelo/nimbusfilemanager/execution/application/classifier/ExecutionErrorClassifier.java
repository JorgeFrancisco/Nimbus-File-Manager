package br.com.jorgemelo.nimbusfilemanager.execution.application.classifier;

import java.io.FileNotFoundException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.util.Locale;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ExecutionErrorType;

@Component
public class ExecutionErrorClassifier {

	public ExecutionErrorType classify(Throwable throwable) {
		Throwable root = rootCause(throwable);

		String message = root.getMessage() == null ? "" : root.getMessage().toLowerCase(Locale.ROOT);

		if (message.contains("verificação cíclica de redundância") || message.contains("cyclic redundancy check")
				|| message.contains("crc")) {
			return ExecutionErrorType.CRC_ERROR;
		}

		if (root instanceof AccessDeniedException || message.contains("access is denied")
				|| message.contains("acesso negado")) {
			return ExecutionErrorType.ACCESS_DENIED;
		}

		if (root instanceof NoSuchFileException || root instanceof FileNotFoundException
				|| message.contains("não é possível encontrar") || message.contains("cannot find")
				|| message.contains("file not found")) {
			return ExecutionErrorType.FILE_NOT_FOUND;
		}

		if (message.contains("hash")) {
			return ExecutionErrorType.HASH_ERROR;
		}

		if (message.contains("metadata") || message.contains("exif") || message.contains("mediainfo")) {
			return ExecutionErrorType.METADATA_ERROR;
		}

		return ExecutionErrorType.UNKNOWN;
	}

	private Throwable rootCause(Throwable throwable) {
		Throwable current = throwable;

		while (current.getCause() != null) {
			current = current.getCause();
		}

		return current;
	}
}