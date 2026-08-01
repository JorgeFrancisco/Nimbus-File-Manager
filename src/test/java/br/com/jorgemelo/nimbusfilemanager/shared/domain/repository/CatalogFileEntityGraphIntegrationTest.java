package br.com.jorgemelo.nimbusfilemanager.shared.domain.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
@SpringBootTest
@Transactional
@Testcontainers
class CatalogFileEntityGraphIntegrationTest {

	private static final String FILE_KEY = "D:\\Media\\graph.jpg";

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

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