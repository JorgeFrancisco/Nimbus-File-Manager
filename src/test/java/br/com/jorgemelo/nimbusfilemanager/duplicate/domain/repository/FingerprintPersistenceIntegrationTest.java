package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository;

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

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintFailureReason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.FingerprintFailure;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFileLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

/**
 * Persistence of the fingerprint/failure tables, which are linked to
 * {@code catalog_file} by a loose id rather than a managed JPA relationship:
 * deleting the file must cascade them away through the FK
 * {@code ON DELETE CASCADE}, and the exhausted-failure lookup must expose the
 * file's current path and last error.
 */
@SpringBootTest
@Transactional
@Testcontainers
class FingerprintPersistenceIntegrationTest {

	private static final String ALGORITHM = "FFMPEG_LANCZOS_PHASH_256_V1";

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private MediaFingerprintRepository mediaFingerprintRepository;

	@Autowired
	private FingerprintFailureRepository fingerprintFailureRepository;

	@Test
	void deletingCatalogFileCascadesToFingerprintsAndFailures() {
		Long id = persistCatalogFile("cascade").getId();

		mediaFingerprintRepository.saveAndFlush(MediaFingerprint.builder().catalogFileId(id)
				.kind(FingerprintKind.PHOTO_PHASH).algorithm(ALGORITHM).sampleIndex(0).hashBytes(new byte[32])
				.sampleBytes(new byte[1024]).computedAt(LocalDateTime.now()).build());
		fingerprintFailureRepository.saveAndFlush(FingerprintFailure.builder().catalogFileId(id)
				.kind(FingerprintKind.PHOTO_PHASH).algorithm(ALGORITHM).attempts(1).build());

		Assertions.assertThat(mediaFingerprintRepository.existsByCatalogFileIdAndKindAndAlgorithmAndSampleIndex(id,
				FingerprintKind.PHOTO_PHASH, ALGORITHM, 0)).isTrue();
		Assertions.assertThat(fingerprintFailureRepository.findByCatalogFileIdAndKindAndAlgorithm(id,
				FingerprintKind.PHOTO_PHASH, ALGORITHM)).isPresent();

		catalogFileRepository.deleteById(id);
		catalogFileRepository.flush();

		Assertions.assertThat(mediaFingerprintRepository.existsByCatalogFileIdAndKindAndAlgorithmAndSampleIndex(id,
				FingerprintKind.PHOTO_PHASH, ALGORITHM, 0)).as("fingerprint cascaded away").isFalse();
		Assertions.assertThat(fingerprintFailureRepository.findByCatalogFileIdAndKindAndAlgorithm(id,
				FingerprintKind.PHOTO_PHASH, ALGORITHM)).as("failure cascaded away").isEmpty();
	}

	@Test
	void exhaustedFingerprintFailuresExposeCurrentPathAndError() {
		CatalogFile file = persistCatalogFile("failure-details");

		fingerprintFailureRepository.saveAndFlush(FingerprintFailure.builder().catalogFileId(file.getId())
				.kind(FingerprintKind.PHOTO_PHASH).algorithm(ALGORITHM).attempts(3)
				.reason(FingerprintFailureReason.DECODER_REFUSED).lastError("decode failed").build());

		Assertions
				.assertThat(
						fingerprintFailureRepository.findExhaustedWithPath(FingerprintKind.PHOTO_PHASH, ALGORITHM, 3))
				.anySatisfy(failure -> {
					Assertions.assertThat(failure.path()).isEqualTo(file.getLocation().getCurrentPath());
					Assertions.assertThat(failure.error()).isEqualTo("decode failed");
				});
	}

	private CatalogFile persistCatalogFile(String key) {
		String path = "C:/test/" + key + "-" + System.nanoTime() + ".jpg";

		CatalogFile file = CatalogFile.builder().fileKey("fingerprint-" + key + "-" + System.nanoTime())
				.fileName(key + ".jpg").extension("jpg").sizeBytes(1L).modifiedAt(LocalDateTime.now())
				.fileType(FileType.PHOTO).build();
		file.setLocation(CatalogFileLocation.builder().catalogFile(file).currentPath(path).currentFolder("C:/test")
				.originalPath(path).originalFolder("C:/test").build());

		return catalogFileRepository.saveAndFlush(file);
	}
}