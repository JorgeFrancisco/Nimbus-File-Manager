package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;


@Service
public class MimeTypeService {

	private static final String DEFAULT_MIME_TYPE = "application/octet-stream";

	private final MimeDetector mimeDetector;

	@Autowired
	public MimeTypeService() {
		// A single Tika instance is reused across all calls: Tika's constructor parses
		// the
		// default MIME type configuration from the classpath, which is wasteful to redo
		// for
		// every single file scanned.
		this(new Tika()::detect);
	}

	MimeTypeService(MimeDetector mimeDetector) {
		this.mimeDetector = mimeDetector;
	}

	public String detect(Path file) {
		FileValidationUtils.validateFile(file);

		try {
			String mimeType = mimeDetector.detect(file);

			if (mimeType == null || mimeType.isBlank()) {
				return DEFAULT_MIME_TYPE;
			}

			return mimeType;
		} catch (IOException _) {
			return DEFAULT_MIME_TYPE;
		}
	}
}