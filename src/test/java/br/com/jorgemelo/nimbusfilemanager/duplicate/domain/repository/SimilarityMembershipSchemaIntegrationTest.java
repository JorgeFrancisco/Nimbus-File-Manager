package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

/**
 * What a group may and may not say about the files in it.
 *
 * <p>
 * A group is read as one row per file with one verdict each, and the Duplicates
 * screen acts on those verdicts. A file written twice into the same group could
 * therefore be shown as the original to keep and offered for deletion at once,
 * and the count the group carries would describe a population it does not hold.
 *
 * <p>
 * The other half is the opposite rule, and it is why membership deliberately
 * has no foreign key: a member whose catalogued file has since been purged
 * keeps its place. The group said this many files were alike, and a database
 * that quietly removed the row would make the answer misreport itself.
 */
class SimilarityMembershipSchemaIntegrationTest extends SharedPostgresIntegrationTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Test
	void refusesTheSameFileTwiceInOneGroup() {
		long group = group();
		UUID file = catalogued();

		member(group, file, "KEEP", 0);

		assertThatThrownBy(() -> member(group, file, "DELETE_CANDIDATE", 1))
				.hasMessageContaining("uk_similarity_group_member");
	}

	@Test
	void keepsTakingDifferentFilesIntoTheSameGroup() {
		long group = group();

		member(group, catalogued(), "KEEP", 0);
		member(group, catalogued(), "DELETE_CANDIDATE", 1);

		assertThat(members(group)).isEqualTo(2);
	}

	/**
	 * The same file in two groups is ordinary: an analysis run again produces a
	 * second grouping, and both are on record until the older one is superseded.
	 */
	@Test
	void keepsTheSameFileInDifferentGroups() {
		UUID file = catalogued();

		member(group(), file, "KEEP", 0);
		member(group(), file, "KEEP", 0);

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM similarity_group_member WHERE catalog_file_public_id = ?", Integer.class, file))
				.isEqualTo(2);
	}

	/**
	 * The file is gone from the catalogue and the group still lists it. Proved
	 * here because the absence of a foreign key is what allows it, and an absence
	 * is exactly the kind of thing a later reading of the schema would tidy away.
	 */
	@Test
	void aMemberOutlivesTheCatalogueRowItNames() {
		long group = group();
		UUID file = catalogued();

		member(group, file, "KEEP", 0);

		catalogFileRepository.deleteById(catalogFileRepository.findByCatalogFilePublicId(file).orElseThrow().getId());
		catalogFileRepository.flush();

		assertThat(members(group)).as("the group goes on saying how many files were alike").isEqualTo(1);
	}

	private long group() {
		Long grouping = jdbcTemplate.queryForObject("""
				INSERT INTO similarity_grouping (similarity_grouping_public_id, media_type, algorithm_id,
						grouping_version, parameters_digest, composition_digest, eligible_count, analyzed_count,
						candidate_limit, selection_policy, status, computed_at, group_count, member_count)
				VALUES (gen_random_uuid(), 'PHOTO', 'DCT_PHASH_256_V1', 1, ?, 'c', 0, 0, 8000, 'P', 'BUILDING',
						now(), 0, 0)
				RETURNING id
				""", Long.class, UUID.randomUUID().toString());

		return jdbcTemplate.queryForObject("""
				INSERT INTO similarity_group (grouping_id, similarity_percent, file_count, wasted_bytes, position)
				VALUES (?, 97, 0, 0, 0)
				RETURNING id
				""", Long.class, grouping);
	}

	private UUID catalogued() {
		return catalogFileRepository.saveAndFlush(CatalogFile.builder().extension("jpg").sizeBytes(1L)
				.modifiedAt(Instant.now()).fileType(FileType.PHOTO).build()).getCatalogFilePublicId();
	}

	private void member(long group, UUID file, String verdict, int position) {
		jdbcTemplate.update("""
				INSERT INTO similarity_group_member (group_id, catalog_file_public_id, verdict, reason, position)
				VALUES (?, ?, ?, 'IDENTICAL_COPY', ?)
				""", group, file, verdict, position);
	}

	private int members(long group) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM similarity_group_member WHERE group_id = ?",
				Integer.class, group);
	}
}