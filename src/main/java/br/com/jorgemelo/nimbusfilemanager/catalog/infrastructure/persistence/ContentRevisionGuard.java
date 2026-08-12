package br.com.jorgemelo.nimbusfilemanager.catalog.infrastructure.persistence;

import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Holds a file still while a job decides whether its answer is about the right
 * generation of it.
 *
 * <p>
 * Background work reads a file, spends time on it, and writes afterwards. The
 * generation can move in between, and a plain comparison would not help: between
 * reading the counter and writing the result there is a gap, and the gap is
 * exactly what this exists for.
 *
 * <p>
 * So the read takes a row lock and the caller writes inside the same
 * transaction. Nothing can advance the generation until that transaction ends,
 * which turns a check followed by a write into one indivisible decision - the
 * same guarantee the conditional insert gives, for writers that cannot be
 * expressed as one statement.
 */
@Repository
public class ContentRevisionGuard {

	private static final String LOCK = """
			SELECT content_revision
			FROM catalog_file
			WHERE id = :catalogFileId
			FOR UPDATE
			""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public ContentRevisionGuard(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * @return whether the file is still on the generation the work was started for.
	 * The row stays locked until the caller's transaction ends, so a true answer
	 * remains true for as long as it matters
	 */
	public boolean stillAt(Long catalogFileId, Long expectedContentRevision) {
		if (catalogFileId == null || expectedContentRevision == null) {
			return false;
		}

		return jdbcTemplate.query(LOCK, Map.of("catalogFileId", catalogFileId),
				rs -> rs.next() && expectedContentRevision == rs.getLong("content_revision"));
	}
}