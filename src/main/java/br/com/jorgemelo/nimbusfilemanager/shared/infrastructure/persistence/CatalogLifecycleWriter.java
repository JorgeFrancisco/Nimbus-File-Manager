package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.CatalogFactProvenance;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

/**
 * The door for a file the catalog can no longer find.
 *
 * <p>
 * One statement for the whole batch, because a drive going offline takes a
 * library with it - and one fact per file inside it, because a history is read
 * one photo at a time. The identities are minted here rather than by the
 * database: they are UUIDv7, which carry the moment they were made, and the
 * ordering that buys is worth more than saving a round trip.
 */
@Repository
public class CatalogLifecycleWriter {

	private static final String MARK_MISSING = """
			SELECT mark_catalog_files_missing(CAST(:catalogFileIds AS bigint[]), CAST(:eventIds AS uuid[]),
			     ROW(CAST(:occurredAt AS timestamptz), CAST(:source AS text), CAST(:evidenceKind AS text),
			         NULL, NULL, NULL)::catalog_fact_provenance)
			""";

	private static final String MARK_DELETED = """
			SELECT mark_catalog_files_deleted(CAST(:catalogFileIds AS bigint[]), CAST(:eventIds AS uuid[]),
			     ROW(CAST(:occurredAt AS timestamptz), CAST(:source AS text), CAST(:evidenceKind AS text),
			         NULL, NULL, NULL)::catalog_fact_provenance)
			""";

	private static final String RECORD_PRESENT = """
			SELECT record_catalog_files_present(CAST(:catalogFileIds AS bigint[]), CAST(:eventIds AS uuid[]),
			     ROW(CAST(:occurredAt AS timestamptz), CAST(:source AS text), CAST(:evidenceKind AS text),
			         NULL, NULL, NULL)::catalog_fact_provenance)
			""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public CatalogLifecycleWriter(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * @return how many files actually changed state, which is not how many were
	 * asked about: one already missing, or deliberately removed, is left alone and
	 * produces no fact
	 */
	public int markMissing(List<Long> catalogFileIds, CatalogFactProvenance provenance) {
		if (catalogFileIds.isEmpty()) {
			return 0;
		}

		return jdbcTemplate.queryForObject(MARK_MISSING, parameters(catalogFileIds, provenance), Integer.class);
	}

	/**
	 * Records that the user removed these files for good.
	 *
	 * @return how many actually changed state. A folder deleted twice transitions
	 * nothing the second time, so the count is what happened rather than what was
	 * asked for
	 */
	public int markDeleted(List<Long> catalogFileIds, CatalogFactProvenance provenance) {
		if (catalogFileIds.isEmpty()) {
			return 0;
		}

		return jdbcTemplate.queryForObject(MARK_DELETED, parameters(catalogFileIds, provenance), Integer.class);
	}

	/**
	 * Records that files the catalog had lost were met again.
	 *
	 * <p>
	 * Called from the transaction that promoted them, and only for the ones that
	 * really were promoted: a walk that finds an entry already present reports no
	 * reappearance and nothing reaches here.
	 */
	public int recordPresent(List<Long> catalogFileIds, CatalogFactProvenance provenance) {
		if (catalogFileIds.isEmpty()) {
			return 0;
		}

		return jdbcTemplate.queryForObject(RECORD_PRESENT, parameters(catalogFileIds, provenance), Integer.class);
	}

	/**
	 * The identities are minted here rather than by the database, for the reason
	 * every other fact in this schema is: a UUIDv7 carries the time it was made, and
	 * a value generated down there would be a v4 - the ordering the catalog reads
	 * its history by, thrown away at the last step.
	 */
	private static MapSqlParameterSource parameters(List<Long> catalogFileIds, CatalogFactProvenance provenance) {
		UUID[] eventIds = new UUID[catalogFileIds.size()];

		for (int index = 0; index < eventIds.length; index++) {
			eventIds[index] = UuidV7.generate();
		}

		return new MapSqlParameterSource().addValue("catalogFileIds", catalogFileIds.toArray(Long[]::new))
				.addValue("eventIds", eventIds)
				.addValue("occurredAt", OffsetDateTime.ofInstant(provenance.occurredAt(), ZoneOffset.UTC))
				.addValue("source", provenance.source()).addValue("evidenceKind", provenance.evidence());
	}
}