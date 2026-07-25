package br.com.jorgemelo.nimbusfilemanager.geolocation.application.boundary;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.BoundaryMetadata;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;

/**
 * Persistence of the boundary dataset metadata.json: round-trip, resilience to
 * a corrupt file, creation of missing directories and deletion.
 */
class BoundaryMetadataStoreTest {

	private final WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	@TempDir
	Path geodata;

	private BoundaryMetadataStore store;

	@BeforeEach
	void setUp() {
		when(workspaceManager.geodata()).thenReturn(geodata);

		store = new BoundaryMetadataStore(workspaceManager, objectMapper);
	}

	@Test
	void readReturnsEmptyWhenMetadataFileIsAbsent() {
		Assertions.assertThat(store.read()).isEmpty();
	}

	@Test
	void writesAndReadsMetadataBack() {
		LocalDateTime now = LocalDateTime.parse("2026-07-12T10:15:00");
		BoundaryMetadata metadata = BoundaryMetadata.builder().provider("geoBoundaries").license("ODbL").version("v1")
				.importedRecords(42).sizeBytes(1024).downloadedAt(now).importedAt(now).build();

		store.write(metadata);

		Optional<BoundaryMetadata> read = store.read();

		Assertions.assertThat(read).isPresent();
		Assertions.assertThat(read.get().getProvider()).isEqualTo("geoBoundaries");
		Assertions.assertThat(read.get().getLicense()).isEqualTo("ODbL");
		Assertions.assertThat(read.get().getVersion()).isEqualTo("v1");
		Assertions.assertThat(read.get().getImportedRecords()).isEqualTo(42);
		Assertions.assertThat(read.get().getSizeBytes()).isEqualTo(1024);
		Assertions.assertThat(read.get().getImportedAt()).isEqualTo(now);
	}

	@Test
	void writeCreatesMissingParentDirectories() {
		Path nested = geodata.resolve("nested").resolve("geodata");

		when(workspaceManager.geodata()).thenReturn(nested);

		store.write(BoundaryMetadata.builder().provider("geoBoundaries").build());

		Assertions.assertThat(nested.resolve(BoundaryMetadataStore.METADATA_FILE)).exists();
	}

	@Test
	void readReturnsEmptyWhenFileIsCorrupt() throws IOException {
		Files.writeString(geodata.resolve(BoundaryMetadataStore.METADATA_FILE), "{ this is not valid json");

		Assertions.assertThat(store.read()).isEmpty();
	}

	@Test
	void deleteRemovesMetadataAndIsSafeWhenAbsent() {
		store.delete(); // nothing to delete, must not throw

		store.write(BoundaryMetadata.builder().provider("geoBoundaries").build());

		Assertions.assertThat(geodata.resolve(BoundaryMetadataStore.METADATA_FILE)).exists();

		store.delete();

		Assertions.assertThat(store.read()).isEmpty();
		Assertions.assertThat(geodata.resolve(BoundaryMetadataStore.METADATA_FILE)).doesNotExist();
	}
}