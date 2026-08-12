package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import br.com.jorgemelo.nimbusfilemanager.shared.TestPostgres;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentObservation;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentState;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

/**
 * A content change is one thing or nothing.
 *
 * <p>
 * The generation, the derived rows it invalidates, the fact it records and the
 * rebuild it asks for are four writes that only make sense together: a
 * half-applied change would leave a file describing bytes it no longer holds,
 * or a queued rebuild for a change that never happened. The transaction is what
 * holds them together, and this proves it by breaking the last step.
 *
 * <p>
 * Runs against a database of its own rather than the shared one, because the
 * proof is about what survives a commit boundary - and a test whose own
 * rollback is the isolation could not tell the difference between the write
 * being undone and never having been made.
 */
@SpringBootTest
@Testcontainers
class ContentChangeRollbackIntegrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = TestPostgres.container();

	private static final String KNOWN = "a".repeat(64);
	private static final String DIFFERENT = "b".repeat(64);
	private static final Instant SEEN_AT = Instant.parse("2026-08-13T21:44:00Z");

	@Autowired
	private ContentReconciliation contentReconciliation;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/** The last step of the change, made to fail where a queue outage would. */
	@MockitoBean
	private ContentMetadataRebuild contentMetadataRebuild;

	private Long file;

	@BeforeEach
	void catalogued() {
		CatalogFile saved = CatalogFiles.catalogued(new TransactionTemplate(transactionManager),
				catalogFileRepository, catalogFileLocationRepository,
				Path.of("D:", "library", "photo.jpg"));

		file = saved.getId();

		jdbcTemplate.update("UPDATE catalog_file SET sha256 = ?, size_bytes = 1024, modified_at = ? WHERE id = ?",
				KNOWN, Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")), file);

		jdbcTemplate.update("""
				INSERT INTO media_fingerprint (catalog_file_id, kind, algorithm, sample_index, hash_bytes,
					sample_bytes, computed_at)
				VALUES (?, 'PHOTO_PHASH', 'FFMPEG_LANCZOS_PHASH_256_V1', 0, ?, ?, now())
				""", file, new byte[32], new byte[1024]);
	}

	@AfterEach
	void clean() {
		jdbcTemplate.update("DELETE FROM catalog_file WHERE id = ?", file);
	}

	@Test
	void aChangeThatCouldNotAskForItsRebuildLeavesTheFileOnTheGenerationItHad() {
		doThrow(new IllegalStateException("the queue is gone")).when(contentMetadataRebuild).rebuild(any(), any());

		long generation = revision();

		CatalogFile known = catalogFileRepository.findById(file).orElseThrow();

		ContentObservation observation = new ContentObservation(new ContentState(DIFFERENT, 4096L, SEEN_AT, null),
				CatalogEventSources.WATCHER, SEEN_AT);

		Assertions.assertThatThrownBy(() -> contentReconciliation.reconcile(known, observation))
				.isInstanceOf(IllegalStateException.class);

		Assertions.assertThat(revision()).as("the generation the derived rows were computed from")
				.isEqualTo(generation);
		Assertions.assertThat(sha()).isEqualTo(KNOWN);
		Assertions.assertThat(fingerprints()).as("what described the bytes still describes them").isOne();
		Assertions.assertThat(events()).as("a fact for something that did not happen").isZero();
		Assertions.assertThat(rebuildsQueued()).as("and no request to repair it").isZero();
	}

	/**
	 * The same proof through the other door. A move that proved a digest arrives
	 * here already holding a transaction, so nothing about the callers would show
	 * whether this entry point carries one of its own - and it has to, because
	 * what it reaches is the same all-or-nothing write.
	 */
	@Test
	void aDigestProvedByAnOperationRollsBackTheSameWay() {
		doThrow(new IllegalStateException("the queue is gone")).when(contentMetadataRebuild).rebuild(any(), any());

		long generation = revision();

		CatalogFile known = catalogFileRepository.findById(file).orElseThrow();

		Assertions
				.assertThatThrownBy(() -> contentReconciliation.reconcileFromDigest(known, DIFFERENT, 4096L,
						CatalogEventSources.ORGANIZATION, SEEN_AT))
				.isInstanceOf(IllegalStateException.class);

		Assertions.assertThat(revision()).as("the generation the derived rows were computed from")
				.isEqualTo(generation);
		Assertions.assertThat(sha()).isEqualTo(KNOWN);
		Assertions.assertThat(fingerprints()).as("what described the bytes still describes them").isOne();
		Assertions.assertThat(events()).as("a fact for something that did not happen").isZero();
	}

	private long revision() {
		return jdbcTemplate.queryForObject("SELECT content_revision FROM catalog_file WHERE id = ?", Long.class, file);
	}

	private String sha() {
		return jdbcTemplate.queryForObject("SELECT sha256 FROM catalog_file WHERE id = ?", String.class, file);
	}

	private int fingerprints() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM media_fingerprint WHERE catalog_file_id = ?",
				Integer.class, file);
	}

	private int events() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM catalog_file_event WHERE catalog_file_id = ?",
				Integer.class, file);
	}

	private int rebuildsQueued() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM execution WHERE execution_type = 'METADATA_REBUILD'",
				Integer.class);
	}
}