package br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

/**
 * What a published analysis says after a file is destroyed for good.
 *
 * <p>
 * A quarantined file is still catalogued and still one of the files the
 * analysis found, so its membership stays. A hard purge is the other thing: the
 * bytes are gone on purpose, and a group that still names the file is
 * describing a library that does not exist.
 *
 * <p>
 * The group survives the loss of an ordinary member and stops existing when it
 * loses the one thing it is for. Without its keep it cannot say which copy to
 * keep, and offering the rest for deletion would be offering the last one;
 * below two files it is not a group at all. Choosing a new keep is not the
 * purge's to do - that is similarity's, on its next run, which the purge asks
 * for through the same announcement every other change of the eligible set
 * uses.
 */
class SimilarityPurgeIntegrationTest extends SharedPostgresIntegrationTest {

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Autowired
	private SimilarityPurgeWriter similarityPurgeWriter;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void purgingADeleteCandidateLeavesTheGroupStanding() {
		long grouping = grouping();
		long group = group(grouping, 3);

		CatalogFile keep = member(group, "KEEP", 0);
		CatalogFile other = member(group, "DELETE_CANDIDATE", 1);
		CatalogFile purged = member(group, "DELETE_CANDIDATE", 2);

		hardPurge(purged);

		assertThat(groupsOf(grouping)).as("the group still says which copy to keep").isOne();
		assertThat(membersOf(group)).isEqualTo(2);
		assertThat(memberExists(group, keep)).isTrue();
		assertThat(memberExists(group, other)).isTrue();
		assertThat(fileCountOf(group)).as("the count it publishes is what it holds").isEqualTo(2);
	}

	@Test
	void purgingAReviewCandidateLeavesTheGroupStanding() {
		long grouping = grouping();
		long group = group(grouping, 3);

		member(group, "KEEP", 0);
		member(group, "DELETE_CANDIDATE", 1);

		hardPurge(member(group, "REVIEW", 2));

		assertThat(groupsOf(grouping)).isOne();
		assertThat(membersOf(group)).isEqualTo(2);
		assertThat(fileCountOf(group)).isEqualTo(2);
	}

	@Test
	void purgingTheKeepTakesTheWholeGroup() {
		long grouping = grouping();
		long group = group(grouping, 3);
		long untouched = group(grouping, 2);

		CatalogFile keep = member(group, "KEEP", 0);

		member(group, "DELETE_CANDIDATE", 1);
		member(group, "DELETE_CANDIDATE", 2);
		member(untouched, "KEEP", 0);
		member(untouched, "DELETE_CANDIDATE", 1);

		hardPurge(keep);

		assertThat(groupExists(group)).as("it could no longer say which copy was the original").isFalse();
		assertThat(membersOf(group)).isZero();
		assertThat(groupExists(untouched)).as("the other group of the same analysis is untouched").isTrue();
		assertThat(membersOf(untouched)).isEqualTo(2);
	}

	@Test
	void aGroupLeftWithASingleFileStopsBeingAGroup() {
		long grouping = grouping();
		long group = group(grouping, 2);

		member(group, "KEEP", 0);

		hardPurge(member(group, "DELETE_CANDIDATE", 1));

		assertThat(groupExists(group)).as("one file is not a duplicate of anything").isFalse();
	}

	/**
	 * Quarantine is not a purge: the file is still catalogued, so it is still a
	 * member.
	 */
	@Test
	void aQuarantinedFileKeepsItsPlaceInTheAnalysis() {
		long grouping = grouping();
		long group = group(grouping, 3);

		member(group, "KEEP", 0);
		member(group, "DELETE_CANDIDATE", 1);

		CatalogFile held = member(group, "DELETE_CANDIDATE", 2);

		jdbcTemplate.update("UPDATE catalog_file SET lifecycle_status = 'DELETED' WHERE id = ?", held.getId());

		similarityPurgeWriter.forgetPurgedFiles();

		assertThat(memberExists(group, held)).as("held, not destroyed").isTrue();
		assertThat(membersOf(group)).isEqualTo(3);
		assertThat(groupExists(group)).isTrue();
	}

	/** Nothing purged, nothing to forget - and nothing to announce. */
	@Test
	void aRunWithNothingPurgedChangesNothing() {
		long grouping = grouping();
		long group = group(grouping, 2);

		member(group, "KEEP", 0);
		member(group, "DELETE_CANDIDATE", 1);

		assertThat(similarityPurgeWriter.forgetPurgedFiles()).isZero();
		assertThat(membersOf(group)).isEqualTo(2);
	}

	/** A file in two analyses: both answered, and a group still true stays. */
	@Test
	void aFileInTwoGroupsIsForgottenByBoth() {
		long grouping = grouping();
		long first = group(grouping, 3);
		long second = group(grouping, 3);

		CatalogFile shared = catalogued();

		member(first, "KEEP", 0);
		member(first, "DELETE_CANDIDATE", 1);
		join(first, shared, "DELETE_CANDIDATE", 2);
		join(second, shared, "KEEP", 0);
		member(second, "DELETE_CANDIDATE", 1);
		member(second, "DELETE_CANDIDATE", 2);

		hardPurge(shared);

		assertThat(groupExists(first)).as("it lost an ordinary member and is still true").isTrue();
		assertThat(membersOf(first)).isEqualTo(2);
		assertThat(groupExists(second)).as("it lost the copy it said to keep").isFalse();
	}

	@Test
	void noPlaceholderOfThePurgedFileSurvivesAnywhere() {
		long group = group(grouping(), 3);

		member(group, "KEEP", 0);
		member(group, "DELETE_CANDIDATE", 1);

		CatalogFile purged = member(group, "DELETE_CANDIDATE", 2);

		hardPurge(purged);

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM similarity_group_member WHERE catalog_file_public_id = ?", Integer.class,
				purged.getCatalogFilePublicId())).isZero();
	}

	/**
	 * The bytes are destroyed and the catalogue row with them, which is what the
	 * writer answers to.
	 */
	private void hardPurge(CatalogFile file) {
		jdbcTemplate.update("DELETE FROM catalog_file WHERE id = ?", file.getId());

		similarityPurgeWriter.forgetPurgedFiles();
	}

	private CatalogFile catalogued() {
		return CatalogFiles.catalogued(catalogFileRepository, catalogFileLocationRepository,
				Path.of("S:", "library", UUID.randomUUID() + ".jpg"));
	}

	private CatalogFile member(long group, String verdict, int position) {
		CatalogFile file = catalogued();

		join(group, file, verdict, position);

		return file;
	}

	private void join(long group, CatalogFile file, String verdict, int position) {
		jdbcTemplate.update("""
				INSERT INTO similarity_group_member (group_id, catalog_file_public_id, verdict, reason, position)
				VALUES (?, ?, ?, 'IDENTICAL_COPY', ?)
				""", group, file.getCatalogFilePublicId(), verdict, position);
	}

	private long grouping() {
		return jdbcTemplate.queryForObject("""
				INSERT INTO similarity_grouping (similarity_grouping_public_id, media_type, algorithm_id,
						grouping_version, parameters_digest, composition_digest, eligible_count, analyzed_count,
						candidate_limit, selection_policy, status, computed_at, group_count, member_count)
				VALUES (gen_random_uuid(), 'PHOTO', 'DCT_PHASH_256_V1', 1, ?, 'c', 0, 0, 8000, 'P', 'ACTIVE', now(),
						0, 0)
				RETURNING id
				""", Long.class, UUID.randomUUID().toString());
	}

	private long group(long grouping, int fileCount) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO similarity_group (grouping_id, similarity_percent, file_count, wasted_bytes, position)
				VALUES (?, 97, ?, 0, 0)
				RETURNING id
				""", Long.class, grouping, fileCount);
	}

	private boolean groupExists(long group) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM similarity_group WHERE id = ?", Integer.class,
				group) == 1;
	}

	private int groupsOf(long grouping) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM similarity_group WHERE grouping_id = ?",
				Integer.class, grouping);
	}

	private int membersOf(long group) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM similarity_group_member WHERE group_id = ?",
				Integer.class, group);
	}

	private boolean memberExists(long group, CatalogFile file) {
		return jdbcTemplate.queryForObject("""
				SELECT count(*) FROM similarity_group_member WHERE group_id = ? AND catalog_file_public_id = ?
				""", Integer.class, group, file.getCatalogFilePublicId()) == 1;
	}

	private int fileCountOf(long group) {
		return jdbcTemplate.queryForObject("SELECT file_count FROM similarity_group WHERE id = ?", Integer.class,
				group);
	}
}