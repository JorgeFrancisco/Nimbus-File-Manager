package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentObservation;
import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentState;
import br.com.jorgemelo.nimbusfilemanager.catalog.domain.enums.ContentOutcome;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedMember;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Reason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Verdict;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence.SimilarityGroupingWriter;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventEvidence;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.CatalogEventSources;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FilesystemIdentityKind;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.FilesystemIdentity;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;

/**
 * The boundary that decides what a look at a file's bytes means.
 *
 * <p>
 * Three answers with nothing in common: a digest for a file nobody had hashed
 * is knowledge gained and changes no generation; a digest that agrees settles
 * the argument even when the cheap facts disagree; and a digest that differs
 * means the bytes on record are gone, along with everything derived from them.
 *
 * <p>
 * Proved against the engine because every one of those is enforced there - the
 * compare-and-set on {@code content_revision}, the deletes of the derived rows
 * and the fact written beside them are one statement's worth of atomicity, and
 * a test with the writer mocked would be asserting its own stub.
 */
class ContentReconciliationIntegrationTest extends SharedPostgresIntegrationTest {

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
	private SimilarityGroupingWriter similarityGroupingWriter;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@PersistenceContext
	private EntityManager entityManager;

	private CatalogFile file;

	@BeforeEach
	void catalogued() {
		file = CatalogFiles.catalogued(catalogFileRepository,
				catalogFileLocationRepository, Path.of("D:", "library", "photo.jpg"));
	}

	/**
	 * Hashing is opt-in on a scan, so a catalog holds files nobody has ever read.
	 * The first digest is knowledge gained about the same bytes, not a change of
	 * them: a generation change would tell every derived row that what it was
	 * computed from is gone, and nothing is.
	 */
	@Test
	void aDigestForAFileNobodyHadHashedIsLearnedWithoutStartingANewGeneration() {
		long generation = revisionOf(file);

		seedFingerprint();

		contentReconciliation.reconcile(file, new ContentObservation(new ContentState(KNOWN, 2048L, SEEN_AT, null),
				CatalogEventSources.INVENTORY, SEEN_AT));

		Assertions.assertThat(shaOf(file)).isEqualTo(KNOWN);
		Assertions.assertThat(revisionOf(file)).as("nothing about the bytes changed").isEqualTo(generation);
		Assertions.assertThat(fingerprintsOf(file)).as("nothing derived was invalidated").isOne();
		Assertions.assertThat(eventsOf(file)).isEmpty();
	}

	/**
	 * A timestamp moves without the content moving - a backup tool touching the
	 * file, a copy landing with a new mtime. The digest settles that these are the
	 * same bytes, and what was seen is recorded so the next walk does not suspect
	 * this file and pay for the same reading, pass after pass.
	 */
	@Test
	void bytesThatAgreeSettleTheDescriptionThatDoesNot() {
		hashedAs(KNOWN, 1024L, Instant.parse("2020-01-01T00:00:00Z"));

		long generation = revisionOf(file);

		contentReconciliation.reconcile(reloaded(), new ContentObservation(new ContentState(KNOWN, 4096L, SEEN_AT,
				null), CatalogEventSources.INVENTORY, SEEN_AT));

		Assertions.assertThat(revisionOf(file)).as("the same bytes are the same generation").isEqualTo(generation);
		Assertions.assertThat(eventsOf(file)).isEmpty();
		Assertions.assertThat(sizeOf(file)).as("what the disk says about the file it holds").isEqualTo(4096L);
		Assertions.assertThat(modifiedAtOf(file)).isEqualTo(SEEN_AT);
	}

	/**
	 * Different bytes at the same path. Everything the catalog derived describes
	 * content that is gone, and a rebuild is queued in the same transaction as the
	 * change that made it necessary - so a change that rolls back takes the
	 * request with it.
	 */
	@Test
	void differentBytesStartANewGenerationAndClearWhatDescribedTheOldOnes() {
		hashedAs(KNOWN, 1024L, Instant.parse("2020-01-01T00:00:00Z"));

		long generation = revisionOf(file);

		seedFingerprint();
		seedFailure();
		seedMetadata();

		CatalogFile other = CatalogFiles.catalogued(catalogFileRepository,
				catalogFileLocationRepository, Path.of("D:", "library",
				"other.jpg"));

		long group = seedGroupOf(file, other);

		contentReconciliation.reconcile(reloaded(), new ContentObservation(new ContentState(DIFFERENT, 4096L, SEEN_AT,
				null), CatalogEventSources.WATCHER, SEEN_AT));

		Assertions.assertThat(revisionOf(file)).as("a generation nothing derived was computed from")
				.isEqualTo(generation + 1);
		Assertions.assertThat(shaOf(file)).isEqualTo(DIFFERENT);
		Assertions.assertThat(sizeOf(file)).isEqualTo(4096L);
		Assertions.assertThat(modifiedAtOf(file)).isEqualTo(SEEN_AT);

		Assertions.assertThat(fingerprintsOf(file)).isZero();
		Assertions.assertThat(failuresOf(file)).isZero();
		Assertions.assertThat(metadataOf(file)).isZero();
		Assertions.assertThat(membershipsOf(file)).isZero();

		Assertions.assertThat(membersOfGroup(group)).as("the other members were grouped by evidence that still stands")
				.isOne();

		Assertions.assertThat(rebuildsQueued()).as("nothing goes looking for metadata that is missing").isOne();

		Assertions.assertThat(eventsOf(file)).singleElement()
				.isEqualTo(Map.of("type", "CONTENT_CHANGED", "source", CatalogEventSources.WATCHER, "evidence",
						CatalogEventEvidence.CONTENT_DIGEST_CHANGED));
	}

	/**
	 * Two workers reading the same unhashed file at once. The second finds the
	 * digest already there and agreeing, which is not a change and not a failure -
	 * it is the same answer arriving twice.
	 */
	@Test
	void aDigestAnotherWorkerAlreadyLearnedIsTheSameAnswerArrivingTwice() {
		contentReconciliation.reconcile(reloaded(), new ContentObservation(new ContentState(KNOWN, 2048L, SEEN_AT,
				null), CatalogEventSources.INVENTORY, SEEN_AT));

		Assertions.assertThat(contentReconciliation.reconcile(reloaded(), new ContentObservation(new ContentState(
				KNOWN, 2048L, SEEN_AT, null), CatalogEventSources.INVENTORY, SEEN_AT)))
				.isEqualTo(ContentOutcome.ALREADY_CONVERGED);

		Assertions.assertThat(eventsOf(file)).isEmpty();
	}

	/**
	 * The same race with a different answer: somebody learned a digest between this
	 * one's read and its write. Writing over it would settle by arriving last
	 * rather than by knowing more.
	 */
	@Test
	void aDigestAnotherWorkerLearnedFirstIsNotWrittenOver() {
		CatalogFile unhashed = reloaded();

		jdbcTemplate.update("UPDATE catalog_file SET sha256 = ? WHERE id = ?", KNOWN, file.getId());

		Assertions.assertThat(contentReconciliation.reconcile(unhashed, new ContentObservation(new ContentState(
				DIFFERENT, 2048L, SEEN_AT, null), CatalogEventSources.INVENTORY, SEEN_AT)))
				.isEqualTo(ContentOutcome.CONFLICT);

		Assertions.assertThat(shaOf(file)).isEqualTo(KNOWN);
	}

	/**
	 * An editor that saves by writing a temporary file and renaming it over the
	 * original leaves the same bytes behind a different object. Nothing about the
	 * content changed, and the placement records which object holds it now - which
	 * is what the next observation will be compared against.
	 */
	@Test
	void theSameBytesBehindADifferentObjectRecordThatObject() {
		hashedAs(KNOWN, 1024L, SEEN_AT);

		identifiedAs("11");

		long generation = revisionOf(file);

		contentReconciliation.reconcile(reloaded(), new ContentObservation(new ContentState(KNOWN, 1024L, SEEN_AT,
				identity("22")), CatalogEventSources.WATCHER, SEEN_AT));

		Assertions.assertThat(identityOf(file)).isEqualTo("22");
		Assertions.assertThat(revisionOf(file)).as("the bytes are the ones on record, whatever holds them")
				.isEqualTo(generation);
		Assertions.assertThat(eventsOf(file)).isEmpty();
	}

	/**
	 * A verified move proves the bytes and never stats the destination, so it
	 * arrives with a digest and nothing else. What it did not look at must not
	 * overwrite what somebody who did look recorded.
	 */
	@Test
	void anObserverThatOnlyProvedTheBytesDoesNotOverwriteHowTheFileDescribesItself() {
		hashedAs(KNOWN, 1024L, Instant.parse("2020-01-01T00:00:00Z"));

		contentReconciliation.reconcileFromDigest(reloaded(), KNOWN, null, CatalogEventSources.ORGANIZATION, SEEN_AT);

		Assertions.assertThat(sizeOf(file)).isEqualTo(1024L);
		Assertions.assertThat(modifiedAtOf(file)).isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
	}

	/** Nothing was proved, so there is nothing to settle. */
	@Test
	void anOperationThatProvedNoDigestSettlesNothing() {
		Assertions.assertThat(contentReconciliation.reconcileFromDigest(reloaded(), null, 10L,
				CatalogEventSources.ORGANIZATION, SEEN_AT)).isEqualTo(ContentOutcome.ALREADY_CONVERGED);
	}

	/**
	 * A job that read the file, was overtaken, and arrives with an answer about
	 * bytes the catalog has already replaced. Refused: it describes a generation
	 * that no longer exists, and writing it would resurrect it.
	 */
	@Test
	void anObservationAboutAnEarlierGenerationIsRefused() {
		hashedAs(KNOWN, 1024L, SEEN_AT);

		CatalogFile stale = reloaded();

		// Somebody else's change lands first, taking the file to a new generation.
		contentReconciliation.reconcile(reloaded(), new ContentObservation(new ContentState(DIFFERENT, 2048L, SEEN_AT,
				null), CatalogEventSources.WATCHER, SEEN_AT));

		long current = revisionOf(file);

		contentReconciliation.reconcile(stale, new ContentObservation(new ContentState("c".repeat(64), 8192L, SEEN_AT,
				null), CatalogEventSources.INVENTORY, SEEN_AT));

		Assertions.assertThat(revisionOf(file)).as("the late answer did not start a generation of its own")
				.isEqualTo(current);
		Assertions.assertThat(shaOf(file)).as("nor overwrote the one that won").isEqualTo(DIFFERENT);
		Assertions.assertThat(eventsOf(file)).as("and said nothing about a file it was not describing").hasSize(1);
	}

	/** The object the placement says is holding the path. */
	private void identifiedAs(String value) {
		jdbcTemplate.update("""
				UPDATE catalog_file_location SET filesystem_identity_kind = 'WINDOWS_FILE_ID',
					filesystem_identity_scope = 'volume-under-test', filesystem_identity_value = ?
				WHERE catalog_file_id = ?
				""", value, file.getId());

		entityManager.clear();
	}

	private FilesystemIdentity identity(String value) {
		return new FilesystemIdentity(FilesystemIdentityKind.WINDOWS_FILE_ID, "volume-under-test", value);
	}

	private String identityOf(CatalogFile catalogFile) {
		return jdbcTemplate.queryForObject(
				"SELECT filesystem_identity_value FROM catalog_file_location WHERE catalog_file_id = ?", String.class,
				catalogFile.getId());
	}

	/** The entry as the row now stands, not as the session last saw it. */
	private CatalogFile reloaded() {
		entityManager.clear();

		return catalogFileRepository.findById(file.getId()).orElseThrow();
	}

	/**
	 * The file as the catalog already knows it.
	 *
	 * <p>
	 * Written through the row and then read back into the session, because what
	 * the boundary compares an observation against is the entity it is handed -
	 * and one loaded before this would still describe the file as it was.
	 */
	private void hashedAs(String sha256, long sizeBytes, Instant modifiedAt) {
		jdbcTemplate.update("UPDATE catalog_file SET sha256 = ?, size_bytes = ?, modified_at = ? WHERE id = ?",
				sha256, sizeBytes, Timestamp.from(modifiedAt), file.getId());

		entityManager.clear();
	}

	private void seedFingerprint() {
		jdbcTemplate.update("""
				INSERT INTO media_fingerprint (catalog_file_id, kind, algorithm, sample_index, hash_bytes, sample_bytes,
					computed_at)
				VALUES (?, 'PHOTO_PHASH', 'FFMPEG_LANCZOS_PHASH_256_V1', 0, ?, ?, now())
				""", file.getId(), new byte[32], new byte[1024]);
	}

	private void seedFailure() {
		jdbcTemplate.update("""
				INSERT INTO fingerprint_failure (catalog_file_id, kind, algorithm, attempts, last_error, reason)
				VALUES (?, 'PHOTO_PHASH', 'FFMPEG_LANCZOS_PHASH_256_V1', 1, 'unreadable', 'UNSUPPORTED_FORMAT')
				""", file.getId());
	}

	private void seedMetadata() {
		jdbcTemplate.update("INSERT INTO media_metadata (catalog_file_id, category, subcategory)"
				+ " VALUES (?, 'MEDIA', 'CAMERA')", file.getId());
	}

	/** A published grouping over both files, by the door that publishes them. */
	private long seedGroupOf(CatalogFile first, CatalogFile second) {
		Long grouping = jdbcTemplate.queryForObject("""
				INSERT INTO similarity_grouping (similarity_grouping_public_id, media_type, algorithm_id,
					grouping_version, parameters_digest, composition_digest, eligible_count, analyzed_count,
					candidate_limit, selection_policy, status, computed_at, group_count, member_count)
				VALUES (?, 'PHOTO', 'FFMPEG_LANCZOS_PHASH_256_V1', 1, ?, ?, 2, 2, 8000,
					'OLDEST_ELIGIBLE_ID_FIRST', 'BUILDING', now(), 0, 0)
				RETURNING id
				""", Long.class, UUID.randomUUID(), "p".repeat(64), "c".repeat(64));

		similarityGroupingWriter.write(grouping, List.of(new AnalyzedGroup(97, 1024L,
				List.of(new AnalyzedMember(publicIdOf(first), Verdict.KEEP, Reason.ORIGINAL),
						new AnalyzedMember(publicIdOf(second), Verdict.DELETE_CANDIDATE, Reason.IDENTICAL_COPY)))));

		return grouping;
	}

	private UUID publicIdOf(CatalogFile catalogFile) {
		return jdbcTemplate.queryForObject("SELECT catalog_file_public_id FROM catalog_file WHERE id = ?", UUID.class,
				catalogFile.getId());
	}

	private long revisionOf(CatalogFile catalogFile) {
		return jdbcTemplate.queryForObject("SELECT content_revision FROM catalog_file WHERE id = ?", Long.class,
				catalogFile.getId());
	}

	private String shaOf(CatalogFile catalogFile) {
		return jdbcTemplate.queryForObject("SELECT sha256 FROM catalog_file WHERE id = ?", String.class,
				catalogFile.getId());
	}

	private Long sizeOf(CatalogFile catalogFile) {
		return jdbcTemplate.queryForObject("SELECT size_bytes FROM catalog_file WHERE id = ?", Long.class,
				catalogFile.getId());
	}

	private Instant modifiedAtOf(CatalogFile catalogFile) {
		return jdbcTemplate.queryForObject("SELECT modified_at FROM catalog_file WHERE id = ?", Timestamp.class,
				catalogFile.getId()).toInstant();
	}

	private int fingerprintsOf(CatalogFile catalogFile) {
		return count("SELECT count(*) FROM media_fingerprint WHERE catalog_file_id = ?", catalogFile.getId());
	}

	private int failuresOf(CatalogFile catalogFile) {
		return count("SELECT count(*) FROM fingerprint_failure WHERE catalog_file_id = ?", catalogFile.getId());
	}

	private int metadataOf(CatalogFile catalogFile) {
		return count("SELECT count(*) FROM media_metadata WHERE catalog_file_id = ?", catalogFile.getId());
	}

	private int membershipsOf(CatalogFile catalogFile) {
		return count("""
				SELECT count(*) FROM similarity_group_member m
				JOIN catalog_file f ON f.catalog_file_public_id = m.catalog_file_public_id
				WHERE f.id = ?
				""", catalogFile.getId());
	}

	private int membersOfGroup(long grouping) {
		return count("""
				SELECT count(*) FROM similarity_group_member m
				JOIN similarity_group g ON g.id = m.group_id
				WHERE g.grouping_id = ?
				""", grouping);
	}

	private int rebuildsQueued() {
		return count("SELECT count(*) FROM execution WHERE execution_type = ?", ExecutionType.METADATA_REBUILD.name());
	}

	private List<Map<String, Object>> eventsOf(CatalogFile catalogFile) {
		return jdbcTemplate.queryForList("""
				SELECT event_type AS type, source, evidence_kind AS evidence
				FROM catalog_file_event WHERE catalog_file_id = ? ORDER BY id
				""", catalogFile.getId());
	}

	private int count(String sql, Object argument) {
		return jdbcTemplate.queryForObject(sql, Integer.class, argument);
	}
}