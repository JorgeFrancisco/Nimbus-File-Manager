package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import java.nio.file.Files;
import java.nio.file.Path;

public final class FileValidationUtils {

	private FileValidationUtils() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}

	public static void validateFile(Path file) {
		if (file == null) {
			throw new IllegalArgumentException("File path must not be null.");
		}

		if (!Files.exists(file)) {
			throw new IllegalArgumentException("File does not exist: " + file);
		}

		if (!Files.isRegularFile(file)) {
			throw new IllegalArgumentException("Path is not a regular file: " + file);
		}
	}
}