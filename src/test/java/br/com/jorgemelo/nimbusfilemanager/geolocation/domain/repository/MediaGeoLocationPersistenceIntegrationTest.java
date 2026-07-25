package br.com.jorgemelo.nimbusfilemanager.geolocation.domain.repository;

import java.time.LocalDateTime;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationProvider;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.MediaGeoLocation;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.model.ResolvedPlace;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileCategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationConfidence;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.MediaMetadata;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import jakarta.persistence.EntityManager;

/**
 * Confirms that a {@code MediaGeoLocation}'s {@code manual} flag round-trips
 * through the database as {@code false} when not set (the DB column defaults to
 * {@code FALSE}). The pure-Java default is covered by
 * {@code MediaGeoLocationDefaultTest}; this is the persisted-value counterpart.
 */
@SpringBootTest
@Transactional
@Testcontainers
class MediaGeoLocationPersistenceIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private MediaGeoLocationRepository mediaGeoLocationRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	void manualFalseSurvivesADatabaseRoundTrip() {
		// Persist media_metadata through the CatalogFile aggregate (cascade ALL +
		// @MapsId), not via a dedicated repository - mirrors how the inventory mapper
		// persists it. media_geo_location's FK targets media_metadata(catalog_file_id),
		// so the cascade must create that row for the insert below to satisfy the FK.
		CatalogFile file = CatalogFile.builder().fileKey("geo-manual-" + System.nanoTime()).fileName("manual.jpg")
				.extension("jpg").sizeBytes(1L).modifiedAt(LocalDateTime.now()).fileType(FileType.PHOTO).build();
		file.setMetadata(MediaMetadata.builder().catalogFile(file).category(FileCategory.MEDIA)
				.subcategory(MediaSubcategory.CAMERA).build());

		catalogFileRepository.saveAndFlush(file);

		mediaGeoLocationRepository
				.saveAndFlush(MediaGeoLocation.builder().id(file.getId())
						.place(ResolvedPlace.builder().confidence(LocationConfidence.HIGH)
								.provider(LocationProvider.ADMIN_BOUNDARIES).resolvedAt(LocalDateTime.now()).build())
						.build());

		// Fresh read from the DB (context cleared): confirms false was persisted.
		entityManager.clear();

		Assertions.assertThat(mediaGeoLocationRepository.findById(file.getId())).get()
				.extracting(MediaGeoLocation::isManual).isEqualTo(false);
	}
}