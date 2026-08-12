package br.com.jorgemelo.nimbusfilemanager.catalog.infrastructure.persistence;

import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence.FingerprintWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

/**
 * The two doors that refuse an answer about bytes the catalog has replaced.
 *
 * <p>
 * Everything expensive the product computes - a digest, a perceptual hash, a
 * rebuilt set of metadata - is read from a file that anybody may overwrite
 * while the reading is in flight. The generation the work started on is carried
 * along with it and checked at the moment of writing, so a late answer is
 * discarded rather than recorded against content that no longer exists.
 *
 * <p>
 * Proved against the engine because both are one statement: the check and the
 * write are the same statement on purpose, and a version of this with the read
 * and the write apart would pass while proving the opposite.
 */
class ContentGenerationGuardsIntegrationTest extends SharedPostgresIntegrationTest {

	@Autowired
	private ContentRevisionGuard contentRevisionGuard;

	@Autowired
	private FingerprintWriter fingerprintWriter;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private CatalogFile file;

	@BeforeEach
	void catalogued() {
		file = CatalogFiles.catalogued(catalogFileRepository,
				catalogFileLocationRepository, Path.of("D:", "library", "photo.jpg"));
	}

	@Test
	void aFileStillOnTheGenerationTheWorkStartedOnIsStillTheFileItWasAbout() {
		Assertions.assertThat(contentRevisionGuard.stillAt(file.getId(), revision())).isTrue();
	}

	@Test
	void aFileWhoseContentMovedOnIsNotTheFileTheWorkWasAbout() {
		long started = revision();

		replaced();

		Assertions.assertThat(contentRevisionGuard.stillAt(file.getId(), started))
				.as("the answer describes bytes the catalog has already discarded").isFalse();
	}

	@Test
	void aQuestionWithNothingToAskAboutIsAnsweredNo() {
		Assertions.assertThat(contentRevisionGuard.stillAt(null, revision())).isFalse();
		Assertions.assertThat(contentRevisionGuard.stillAt(file.getId(), null)).isFalse();
		Assertions.assertThat(contentRevisionGuard.stillAt(-1L, revision())).as("no such file").isFalse();
	}

	@Test
	void aFingerprintForTheGenerationItWasComputedFromIsKept() {
		Assertions.assertThat(fingerprintWriter.insertForRevision(fingerprint(0), revision())).isTrue();

		Assertions.assertThat(fingerprints()).isOne();

		Assertions.assertThat(jdbcTemplate.queryForObject(
				"SELECT algorithm FROM media_fingerprint WHERE catalog_file_id = ?", String.class, file.getId()))
				.isEqualTo("FFMPEG_LANCZOS_PHASH_256_V1");
	}

	/**
	 * One row per sampled frame, which is what a video is: the guard is per write
	 * and does not stand in the way of a file having several.
	 */
	@Test
	void everySampleOfTheSameGenerationIsKept() {
		long generation = revision();

		Assertions.assertThat(fingerprintWriter.insertForRevision(fingerprint(0), generation)).isTrue();
		Assertions.assertThat(fingerprintWriter.insertForRevision(fingerprint(1), generation)).isTrue();

		Assertions.assertThat(fingerprints()).isEqualTo(2);
	}

	@Test
	void aFingerprintOfBytesTheCatalogHasReplacedIsNotWritten() {
		long computedFrom = revision();

		replaced();

		Assertions.assertThat(fingerprintWriter.insertForRevision(fingerprint(0), computedFrom))
				.as("refused, and the caller told so it can say why").isFalse();

		Assertions.assertThat(fingerprints()).as("nothing describing content that is gone").isZero();
	}

	private long revision() {
		return jdbcTemplate.queryForObject("SELECT content_revision FROM catalog_file WHERE id = ?", Long.class,
				file.getId());
	}

	/** What a content change does to the row, without going through the door. */
	private void replaced() {
		jdbcTemplate.update("UPDATE catalog_file SET content_revision = content_revision + 1 WHERE id = ?",
				file.getId());
	}

	private MediaFingerprint fingerprint(int sampleIndex) {
		return MediaFingerprint.builder().catalogFileId(file.getId()).kind(FingerprintKind.PHOTO_PHASH)
				.algorithm("FFMPEG_LANCZOS_PHASH_256_V1").sampleIndex(sampleIndex).hashBytes(new byte[32])
				.sampleBytes(new byte[1024]).build();
	}

	private int fingerprints() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM media_fingerprint WHERE catalog_file_id = ?",
				Integer.class, file.getId());
	}
}