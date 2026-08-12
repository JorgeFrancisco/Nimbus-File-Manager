package br.com.jorgemelo.nimbusfilemanager.metadata.application.date;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

interface FileDateReader {

	BasicFileAttributes readAttributes(Path file) throws IOException;
}