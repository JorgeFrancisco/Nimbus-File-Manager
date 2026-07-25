package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.BoundaryMetadata;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads and writes workspace/geodata/metadata.json for the boundary dataset.
 */
@Slf4j
@Component
public class BoundaryMetadataStore {

	static final String METADATA_FILE = "metadata.json";

	private final WorkspaceManager workspaceManager;
	private final ObjectMapper objectMapper;

	public BoundaryMetadataStore(WorkspaceManager workspaceManager, ObjectMapper objectMapper) {
		this.workspaceManager = workspaceManager;
		this.objectMapper = objectMapper;
	}

	public Optional<BoundaryMetadata> read() {
		Path file = metadataFile();

		if (!Files.isRegularFile(file)) {
			return Optional.empty();
		}

		try {
			return Optional.of(objectMapper.readValue(file.toFile(), BoundaryMetadata.class));
		} catch (IOException e) {
			log.warn("Could not read geodata metadata: {}", file, e);

			return Optional.empty();
		}
	}

	public void write(BoundaryMetadata metadata) {
		Path file = metadataFile();

		try {
			Files.createDirectories(file.getParent());

			objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), metadata);
		} catch (IOException e) {
			throw new IllegalStateException("Could not write geodata metadata: " + file, e);
		}
	}

	public void delete() {
		try {
			Files.deleteIfExists(metadataFile());
		} catch (IOException e) {
			log.warn("Could not delete geodata metadata", e);
		}
	}

	private Path metadataFile() {
		return workspaceManager.geodata().resolve(METADATA_FILE);
	}
}