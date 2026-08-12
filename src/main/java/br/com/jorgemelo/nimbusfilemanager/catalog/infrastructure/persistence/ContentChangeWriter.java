package br.com.jorgemelo.nimbusfilemanager.catalog.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.ContentState;
import br.com.jorgemelo.nimbusfilemanager.catalog.domain.enums.ContentOutcome;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.CatalogFactProvenance;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.FilesystemIdentity;

/**
 * The adaptor over the content door, and over the tables that hold what was
 * derived from the bytes it changes.
 *
 * <p>
 * The two live together because they have to commit together. A catalog that
 * says a file holds new bytes while a fingerprint computed from the old ones is
 * still on record is not a catalog with stale data in it - it is a catalog that
 * is wrong, and no window in which that is true may exist.
 *
 * <p>
 * The clearing is written here rather than taught to the database function on
 * purpose: what counts as derived from a file's content spans nearly every
 * feature the product has, and putting that vocabulary inside the structural
 * door would make the one thing they all share know about all of them.
 */
@Repository
public class ContentChangeWriter {

	private static final String APPLY = """
			SELECT outcome, content_revision, event_id
			FROM apply_content_change(CAST(:catalogFileId AS bigint), CAST(:expectedRevision AS bigint),
				 CAST(:expectedSha AS text), CAST(:observedSha AS text), CAST(:observedSize AS bigint),
				 CAST(:observedModifiedAt AS timestamptz), CAST(:eventId AS uuid),
				 ROW(CAST(:occurredAt AS timestamptz), CAST(:source AS text), CAST(:evidenceKind AS text),
					 CAST(:identityKind AS text), CAST(:identityScope AS text),
					 CAST(:identityValue AS text))::catalog_fact_provenance)
			""";

	private static final String LEARN = """
			SELECT learn_content_digest(CAST(:catalogFileId AS bigint), CAST(:observedSha AS text),
				 CAST(:observedSize AS bigint), CAST(:observedModifiedAt AS timestamptz))
			""";

	/**
	 * Everything a look at the bytes produced. Ordered so the rows that point at
	 * others go first, and every one of them is scoped to the single file whose
	 * content moved - a similarity group survives losing one member.
	 */
	private static final String IDENTITY = """
			UPDATE catalog_file_location
			   SET filesystem_identity_kind = CAST(:identityKind AS varchar),
			       filesystem_identity_scope = CAST(:identityScope AS text),
			       filesystem_identity_value = CAST(:identityValue AS text),
			       updated_at = CURRENT_TIMESTAMP
			 WHERE catalog_file_id = CAST(:catalogFileId AS bigint)
			""";

	/**
	 * No generation and no fact: the bytes are the ones on record, and only what
	 * the catalog says about them is being brought up to date. Guarded by the
	 * revision all the same, so a real change that landed in between is never
	 * written over by an observation that predates it.
	 */
	private static final String OBSERVED_FACTS = """
			UPDATE catalog_file
			   SET size_bytes = CAST(:observedSize AS bigint),
			       modified_at = CAST(:observedModifiedAt AS timestamptz)
			 WHERE id = CAST(:catalogFileId AS bigint)
			   AND content_revision = CAST(:expectedRevision AS bigint)
			""";

	private static final String[] DERIVED = {
			"DELETE FROM similarity_group_member WHERE catalog_file_public_id = "
					+ "(SELECT catalog_file_public_id FROM catalog_file WHERE id = :catalogFileId)",
			"DELETE FROM similarity_relation WHERE first_catalog_file_id = :catalogFileId "
					+ "OR second_catalog_file_id = :catalogFileId",
			"DELETE FROM similarity_relation_coverage WHERE catalog_file_id = :catalogFileId",
			"DELETE FROM media_fingerprint WHERE catalog_file_id = :catalogFileId",
			"DELETE FROM fingerprint_failure WHERE catalog_file_id = :catalogFileId",
			"DELETE FROM photo WHERE catalog_file_id = :catalogFileId",
			"DELETE FROM video WHERE catalog_file_id = :catalogFileId",
			"DELETE FROM media_metadata WHERE catalog_file_id = :catalogFileId" };

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public ContentChangeWriter(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * @param known the state the observation was made against, which is what the
	 * database checks it against rather than trusts
	 * @return what the database made of it, which is not always that it applied
	 */
	public ContentOutcome applyContentChange(Long catalogFileId, Long expectedRevision, ContentState known,
			ContentState observed, UUID eventId, CatalogFactProvenance provenance) {
		MapSqlParameterSource parameters = new MapSqlParameterSource()
				.addValue("catalogFileId", catalogFileId).addValue("expectedRevision", expectedRevision)
				.addValue("expectedSha", known.sha256()).addValue("eventId", eventId);

		addObserved(parameters, observed);
		addProvenance(parameters, provenance);

		return ContentOutcome.valueOf(jdbcTemplate.queryForObject(APPLY, parameters,
				(rs, _) -> rs.getString("outcome")));
	}

	/**
	 * @return {@code LEARNED} when the digest was recorded, {@code ALREADY_KNOWN}
	 * when it agreed with one already stored, {@code CONFLICT} when it did not -
	 * which is a content change and belongs to the other door
	 */
	public String learnDigest(Long catalogFileId, ContentState observed) {
		MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("catalogFileId", catalogFileId);

		addObserved(parameters, observed);

		return jdbcTemplate.queryForObject(LEARN, parameters, (rs, _) -> rs.getString(1));
	}

	/**
	 * Records which physical object is holding the path now, for the cases where
	 * nothing about the content changed.
	 *
	 * <p>
	 * Same ownership as the door above: where a file is belongs to the location
	 * door, and which object is at that place - an atomic replace swaps it without
	 * moving anything - belongs here.
	 */
	public void recordFilesystemIdentity(Long catalogFileId, FilesystemIdentity identity) {
		if (identity == null) {
			return;
		}

		jdbcTemplate.update(IDENTITY, new MapSqlParameterSource().addValue("catalogFileId", catalogFileId)
				.addValue("identityKind", identity.kind().name()).addValue("identityScope", identity.scope())
				.addValue("identityValue", identity.value()));
	}

	/**
	 * Records the size and timestamp a reading found, for a file whose bytes were
	 * proved to be the ones already on record.
	 *
	 * <p>
	 * Not a content change and treated as none: no generation is advanced, nothing
	 * derived is cleared, and no fact is written, because nothing happened to the
	 * file. What is being corrected is the catalog's own description of it - a sync
	 * client restoring a folder, or anything that touches a timestamp, leaves the
	 * bytes alone and the recorded timestamp wrong.
	 *
	 * <p>
	 * Left wrong it is not merely untidy. The walk suspects content whenever the
	 * cheap facts disagree, so every pass would suspect this file again, read all
	 * of it again, conclude again that nothing changed, and change nothing - for
	 * as long as the file exists.
	 */
	public void recordObservedFacts(Long catalogFileId, Long expectedRevision, ContentState observed) {
		MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("catalogFileId", catalogFileId)
				.addValue("expectedRevision", expectedRevision);

		addObserved(parameters, observed);

		jdbcTemplate.update(OBSERVED_FACTS, parameters);
	}

	/**
	 * Removes everything computed from the previous generation of the bytes.
	 *
	 * <p>
	 * Removal rather than a flag, because for all of these the question a reader
	 * asks is "what is the fingerprint of this file" and the honest answer, until
	 * one is computed again, is that there is none. A row marked stale would still
	 * be returned by every query that does not know to ask.
	 */
	public void clearDerivedState(Long catalogFileId) {
		MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("catalogFileId", catalogFileId);

		for (String statement : DERIVED) {
			jdbcTemplate.update(statement, parameters);
		}
	}

	private static void addObserved(MapSqlParameterSource parameters, ContentState observed) {
		parameters.addValue("observedSha", observed.sha256()).addValue("observedSize", observed.sizeBytes())
				.addValue("observedModifiedAt", observed.modifiedAt() == null ? null
						: OffsetDateTime.ofInstant(observed.modifiedAt(), ZoneOffset.UTC));
	}

	private static void addProvenance(MapSqlParameterSource parameters, CatalogFactProvenance provenance) {
		FilesystemIdentity identity = provenance.identity();

		parameters.addValue("occurredAt", OffsetDateTime.ofInstant(provenance.occurredAt(), ZoneOffset.UTC))
				.addValue("source", provenance.source()).addValue("evidenceKind", provenance.evidence())
				.addValue("identityKind", identity == null ? null : identity.kind().name())
				.addValue("identityScope", identity == null ? null : identity.scope())
				.addValue("identityValue", identity == null ? null : identity.value());
	}
}