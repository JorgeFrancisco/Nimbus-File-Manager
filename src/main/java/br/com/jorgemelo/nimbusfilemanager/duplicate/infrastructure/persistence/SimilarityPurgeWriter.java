package br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a published analysis says about files that no longer exist.
 *
 * <p>
 * A group is a set of files that resemble one another, and membership is
 * deliberately not a foreign key: while a file is quarantined it is still
 * catalogued, still recoverable, and still one of the files the analysis found.
 * Only a hard purge ends that - the bytes are gone on purpose and there is
 * nothing left to be similar to - and then the membership is not history worth
 * keeping, it is a row naming a file the catalog no longer has.
 *
 * <p>
 * Written set-based and keyed on what the catalog can still answer, so it
 * cleans up after every way of purging at once and says the same thing when run
 * twice. It decides nothing about similarity: it removes what is no longer
 * true, and asks for a fresh analysis through the same announcement every other
 * mutation of the eligible set uses.
 */
@Repository
public class SimilarityPurgeWriter {

	/**
	 * A group without its keep is a group that cannot say which copy to keep, and
	 * offering the rest for deletion would be offering the last one. A group with
	 * fewer than two files left is not a group at all. Both go whole - the header
	 * they belong to stays for the groups that are still true.
	 */
	private static final String DROP_INVALID_GROUPS = """
			DELETE FROM similarity_group g
			 WHERE EXISTS (SELECT 1 FROM similarity_group_member m
							WHERE m.group_id = g.id
							  AND m.verdict = 'KEEP'
							  AND NOT EXISTS (SELECT 1 FROM catalog_file f
											   WHERE f.catalog_file_public_id = m.catalog_file_public_id))
				OR (SELECT count(*) FROM similarity_group_member m
					 WHERE m.group_id = g.id
					   AND EXISTS (SELECT 1 FROM catalog_file f
									WHERE f.catalog_file_public_id = m.catalog_file_public_id)) < 2
			""";

	/** What is left over in the groups that survived. */
	private static final String DROP_ORPHAN_MEMBERS = """
			DELETE FROM similarity_group_member m
			 WHERE NOT EXISTS (SELECT 1 FROM catalog_file f
								WHERE f.catalog_file_public_id = m.catalog_file_public_id)
			""";

	/** The count the group publishes, brought back to what it now holds. */
	private static final String RECOUNT_GROUPS = """
			UPDATE similarity_group g
			   SET file_count = counted.members
			  FROM (SELECT group_id, count(*) AS members FROM similarity_group_member GROUP BY group_id) counted
			 WHERE counted.group_id = g.id
			   AND g.file_count <> counted.members
			""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public SimilarityPurgeWriter(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * @return how many memberships and groups stopped describing the library,
	 * which is zero when nothing was purged - and the caller announces nothing on
	 * a zero
	 */
	@Transactional
	public int forgetPurgedFiles() {
		int groups = jdbcTemplate.getJdbcOperations().update(DROP_INVALID_GROUPS);

		int members = jdbcTemplate.getJdbcOperations().update(DROP_ORPHAN_MEMBERS);

		if (groups + members > 0) {
			jdbcTemplate.getJdbcOperations().update(RECOUNT_GROUPS);
		}

		return groups + members;
	}
}