package br.com.jorgemelo.nimbusfilemanager.shared.domain.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;

/**
 * Runs every {@code @EntityGraph} of the catalog repository against the real
 * mapping. An attribute name that does not exist is not a compile error and not
 * a startup error: Hibernate only rejects it when the query executes, so a typo
 * ships and blows up on the one code path that uses that finder - which is how
 * {@code findByFileKeyWithDetails} asked for a "media" attribute the entity
 * never had, failing the cataloguing of every converted video while the rest of
 * the application went on working.
 */
class CatalogFileEntityGraphIntegrationTest extends SharedPostgresIntegrationTest {

	private static final String FILE_KEY = "D:\\Media\\graph.jpg";

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@BeforeEach
	void seed() {
		CatalogFile file = CatalogFile.builder().fileKey(FILE_KEY).fileName("graph.jpg").extension("jpg")
				.sizeBytes(1_024L).modifiedAt(LocalDateTime.now()).fileType(FileType.PHOTO).build();

		file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(FILE_KEY)
				.currentFolder("D:\\Media").originalPath(FILE_KEY).originalFolder("D:\\Media").build());

		catalogFileRepository.saveAndFlush(file);
	}

	@Test
	void singleLookupWithDetailsResolvesItsGraph() {
		Assertions.assertThat(catalogFileRepository.findByFileKeyWithDetails(FILE_KEY)).isPresent();
	}

	@Test
	void batchLookupWithDetailsResolvesItsGraph() {
		Assertions.assertThat(catalogFileRepository.findByFileKeyInWithDetails(List.of(FILE_KEY))).hasSize(1);
	}

	@Test
	void publicIdLookupResolvesItsGraph() {
		CatalogFile stored = catalogFileRepository.findByFileKey(FILE_KEY).orElseThrow();

		Assertions.assertThat(catalogFileRepository.findByPublicIdIn(List.of(stored.getPublicId()))).hasSize(1);
	}
}