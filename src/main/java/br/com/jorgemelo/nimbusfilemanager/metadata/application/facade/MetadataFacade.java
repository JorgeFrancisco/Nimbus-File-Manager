package br.com.jorgemelo.nimbusfilemanager.metadata.application.facade;

import java.nio.file.Path;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileValidationUtils;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataOptions;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.extractor.MetadataExtractor;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.model.MetadataResult;

@Service
public class MetadataFacade {

	private final MetadataExtractor metadataExtractor;

	public MetadataFacade(MetadataExtractor metadataExtractor) {
		this.metadataExtractor = metadataExtractor;
	}

	public MetadataResult extract(Path file, MetadataOptions options) {
		FileValidationUtils.validateFile(file);

		return metadataExtractor.extract(file, options);
	}
}