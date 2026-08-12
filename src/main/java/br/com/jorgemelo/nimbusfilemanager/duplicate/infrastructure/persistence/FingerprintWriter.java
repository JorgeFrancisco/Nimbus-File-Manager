package br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.MediaFingerprint;

/**
 * Writes a fingerprint only if it is still about the file that was read.
 *
 * <p>
 * Fingerprinting is background work: a job reads a photo, spends a while on it,
 * and writes the answer afterwards. If the file was edited in between, that
 * answer describes bytes nobody has any more - and the clearing that happened
 * when the edit was recorded would be quietly undone by a job that started
 * before it.
 *
 * <p>
 * The guard is the write itself rather than a check before it. Reading the
 * revision and then inserting leaves a gap in which the revision can move, and
 * the gap is exactly the case this exists for: the {@code INSERT ... SELECT}
 * finds no row to select from when the generation has moved on, so nothing is
 * written and the caller is told none was.
 */
@Repository
public class FingerprintWriter {

	private static final String INSERT_FOR_REVISION = """
			INSERT INTO media_fingerprint (catalog_file_id, kind, algorithm, sample_index, position_ms, hash,
					hash_bytes, sample_bytes, computed_at)
			SELECT m.id, CAST(:kind AS varchar), CAST(:algorithm AS varchar), CAST(:sampleIndex AS integer),
			       CAST(:positionMs AS bigint), CAST(:hash AS bigint), CAST(:hashBytes AS bytea),
			       CAST(:sampleBytes AS bytea), CURRENT_TIMESTAMP
			FROM catalog_file m
			WHERE m.id = CAST(:catalogFileId AS bigint)
			  AND m.content_revision = CAST(:expectedContentRevision AS bigint)
			""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public FingerprintWriter(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * @param expectedContentRevision the generation the file was on when the work
	 * started
	 * @return whether it was written, which is false when the content moved on
	 */
	public boolean insertForRevision(MediaFingerprint fingerprint, Long expectedContentRevision) {
		MapSqlParameterSource parameters = new MapSqlParameterSource()
				.addValue("catalogFileId", fingerprint.getCatalogFileId())
				.addValue("expectedContentRevision", expectedContentRevision)
				.addValue("kind", fingerprint.getKind().name()).addValue("algorithm", fingerprint.getAlgorithm())
				.addValue("sampleIndex", fingerprint.getSampleIndex())
				.addValue("positionMs", fingerprint.getPositionMs()).addValue("hash", fingerprint.getHash())
				.addValue("hashBytes", fingerprint.getHashBytes())
				.addValue("sampleBytes", fingerprint.getSampleBytes());

		return jdbcTemplate.update(INSERT_FOR_REVISION, parameters) > 0;
	}
}