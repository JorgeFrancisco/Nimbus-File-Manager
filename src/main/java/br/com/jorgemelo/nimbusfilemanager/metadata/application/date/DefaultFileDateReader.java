package br.com.jorgemelo.nimbusfilemanager.metadata.application.date;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

class DefaultFileDateReader implements FileDateReader {

	@Override
	public BasicFileAttributes readAttributes(Path file) throws IOException {
		return Files.readAttributes(file, BasicFileAttributes.class);
	}
}