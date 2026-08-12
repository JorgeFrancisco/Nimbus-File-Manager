package br.com.jorgemelo.nimbusfilemanager.shared.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.PathFlavor;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;

/**
 * Runs every {@code @EntityGraph} of the catalog repository against the real
 * mapping. An attribute name that does not exist is not a compile error and not
 * a startup error: Hibernate only rejects it when the query executes, so a typo
 * ships and blows up on the one code path that uses that finder - which is how
 * the details lookup asked for a "media" attribute the entity never had,
 * failing the cataloguing of every converted video while the rest of the
 * application went on working.
 */
class CatalogFileEntityGraphIntegrationTest extends SharedPostgresIntegrationTest {

	private static final String PATH = "D:\\Media\\graph.jpg";

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@BeforeEach
	void seed() {
		CatalogFile file = CatalogFile.builder().extension("jpg")
				.sizeBytes(1_024L).modifiedAt(Instant.now()).fileType(FileType.PHOTO).build();

		file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(PATH)
				.currentFolder("D:\\Media").pathFlavor(PathFlavor.WINDOWS).build());

		CatalogFiles.catalogued(catalogFileRepository, catalogFileLocationRepository, file);
	}

	@Test
	void batchLookupWithDetailsResolvesItsGraph() {
		CatalogFile stored = stored();

		Assertions.assertThat(catalogFileRepository.findWithDetailsByIdIn(List.of(stored.getId()))).hasSize(1);
	}

	@Test
	void publicIdLookupResolvesItsGraph() {
		Assertions.assertThat(catalogFileRepository
				.findByCatalogFilePublicIdIn(new UUID[] { stored().getCatalogFilePublicId() })).hasSize(1);
	}

	/**
	 * The lookup a path answers now: which file is <em>at</em> this place. It runs
	 * its own graph, so it belongs here for the same reason the others do.
	 */
	@Test
	void placementLookupResolvesItsGraph() {
		Assertions.assertThat(catalogFileLocationRepository.findPresentByPath(PATH, PathFlavor.WINDOWS.name()))
				.isPresent();
	}

	private CatalogFile stored() {
		return catalogFileLocationRepository.findPresentByPath(PATH, PathFlavor.WINDOWS.name()).orElseThrow();
	}
}