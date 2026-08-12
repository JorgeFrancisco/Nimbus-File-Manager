package br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import br.com.jorgemelo.nimbusfilemanager.shared.TestPostgres;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedMember;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Reason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Verdict;

/**
 * What the batched writer actually leaves in the three tables.
 *
 * <p>
 * The writer exists for speed, so every test here is about the thing speed is
 * allowed to cost nothing: the rows. Positions, the link from member to group,
 * the enum spellings and the counts are what the screen reads back, and the
 * screen reads them by {@code position} rather than by id - which is exactly why
 * reserving ids up front is safe and why it still has to be checked.
 *
 * <p>
 * Deliberately larger than one batch in the sizes that matter, so the seams
 * between batches are inside the assertions rather than beyond them.
 */
@SpringBootTest
@Testcontainers
class SimilarityGroupingWriterIntegrationTest {

	/** Past the writer's batch of 1.000, so at least one seam is exercised. */
	private static final int MANY_GROUPS = 1_100;

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = TestPostgres.container();

	@Autowired
	private SimilarityGroupingWriter writer;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Long grouping;

	/**
	 * A real header row, because the groups point at one by foreign key. Written
	 * straight through JDBC: what is under test is the writer, and going through
	 * the publisher to get a header would drag its transaction in with it.
	 */
	@BeforeEach
	void header() {
		grouping = jdbcTemplate.queryForObject("""
				INSERT INTO similarity_grouping (similarity_grouping_public_id, media_type, algorithm_id, grouping_version,
					parameters_digest, composition_digest, eligible_count, analyzed_count, candidate_limit,
					selection_policy, status, computed_at, group_count, member_count)
				VALUES (?, 'PHOTO', 'FFMPEG_LANCZOS_PHASH_256_V1', 1, ?, ?, 120, 120, 8000,
					'OLDEST_ELIGIBLE_ID_FIRST', 'BUILDING', now(), 0, 0)
				RETURNING id
				""", Long.class, UUID.randomUUID(), "p".repeat(64), "c".repeat(64));
	}

	@AfterEach
	void clean() {
		jdbcTemplate.update("DELETE FROM similarity_grouping");
	}

	@Test
	void everyGroupKeepsThePositionAndThePayloadTheAnalysisGaveIt() {
		writer.write(grouping, List.of(group(96, 2048L, 2), group(80, 512L, 3)));

		List<Map<String, Object>> groups = jdbcTemplate.queryForList("""
				SELECT position, grouping_id, similarity_percent, file_count, wasted_bytes
				FROM similarity_group
				ORDER BY position
				""");

		assertThat(groups).hasSize(2);
		assertThat(groups.get(0)).containsEntry("position", 0).containsEntry("grouping_id", grouping)
				.containsEntry("similarity_percent", 96).containsEntry("file_count", 2)
				.containsEntry("wasted_bytes", 2048L);
		assertThat(groups.get(1)).containsEntry("position", 1).containsEntry("similarity_percent", 80)
				.containsEntry("file_count", 3).containsEntry("wasted_bytes", 512L);
	}

	/**
	 * Each member sits under its own group, in the order it was given, with the
	 * verdict and reason spelled the way the mapping spells them. The group link is
	 * the part reserving ids could get wrong, and it is the part nothing downstream
	 * would notice.
	 */
	@Test
	void everyMemberSitsUnderItsOwnGroupInOrder() {
		AnalyzedGroup first = group(96, 2048L, 2);
		AnalyzedGroup second = group(80, 512L, 3);

		writer.write(grouping, List.of(first, second));

		assertThat(publicIdsOf(0)).containsExactlyElementsOf(idsOf(first));
		assertThat(publicIdsOf(1)).containsExactlyElementsOf(idsOf(second));

		List<Map<String, Object>> members = jdbcTemplate.queryForList("""
				SELECT m.position, m.verdict, m.reason
				FROM similarity_group_member m
				JOIN similarity_group g ON g.id = m.group_id
				ORDER BY g.position, m.position
				""");

		assertThat(members).extracting(row -> row.get("position")).containsExactly(0, 1, 0, 1, 2);
		assertThat(members.getFirst()).containsEntry("verdict", "KEEP").containsEntry("reason", "ORIGINAL");
		assertThat(members.get(1)).containsEntry("verdict", "DELETE_CANDIDATE")
				.containsEntry("reason", "IDENTICAL_COPY");
	}

	/** A reason the analysis did not give stays null rather than becoming a word. */
	@Test
	void aMemberWithoutAReasonIsStoredWithoutOne() {
		writer.write(grouping, List.of(new AnalyzedGroup(90, 1L,
				List.of(new AnalyzedMember(UUID.randomUUID(), Verdict.KEEP, null),
						new AnalyzedMember(UUID.randomUUID(), Verdict.DELETE_CANDIDATE, null)))));

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM similarity_group_member WHERE reason IS NULL",
				Integer.class)).isEqualTo(2);
	}

	/**
	 * A result spanning several batches keeps every row and every position. The
	 * batch boundary is where a writer that indexes wrongly starts attributing
	 * members to the previous group, and nothing about the result would look odd.
	 */
	@Test
	void aResultLargerThanOneBatchKeepsEveryRowAndEveryPosition() {
		List<AnalyzedGroup> many = new ArrayList<>(MANY_GROUPS);

		for (int index = 0; index < MANY_GROUPS; index++) {
			many.add(group(90 + index % 10, index, 2));
		}

		writer.write(grouping, many);

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM similarity_group", Integer.class))
				.isEqualTo(MANY_GROUPS);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM similarity_group_member", Integer.class))
				.isEqualTo(MANY_GROUPS * 2);

		assertThat(jdbcTemplate.queryForObject("SELECT count(DISTINCT position) FROM similarity_group", Integer.class))
				.as("every group has a position of its own").isEqualTo(MANY_GROUPS);

		assertThat(jdbcTemplate.queryForList("""
				SELECT g.id
				FROM similarity_group g
				JOIN similarity_group_member m ON m.group_id = g.id
				GROUP BY g.id
				HAVING count(*) <> 2
				""", Long.class)).as("no group borrowed a member from its neighbour").isEmpty();
	}

	/** Nothing to write is not an error, and writes nothing. */
	@Test
	void anAnalysisThatFoundNoGroupsWritesNothing() {
		writer.write(grouping, List.of());

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM similarity_group", Integer.class)).isZero();
	}

	/**
	 * A failure while the members are going in leaves nothing behind - not the
	 * groups that were already written, and not the ones from the batch that
	 * succeeded before it.
	 *
	 * <p>
	 * The member that cannot be stored is in the second batch on purpose: a writer
	 * that committed per batch would leave the first thousand groups and their
	 * members sitting there, and every count would look plausible.
	 */
	@Test
	void aFailureWhileWritingLeavesNoPartialResult() {
		List<AnalyzedGroup> many = new ArrayList<>(MANY_GROUPS);

		for (int index = 0; index < MANY_GROUPS; index++) {
			many.add(index == MANY_GROUPS - 1 ? unwritable() : group(90, index, 2));
		}

		assertThatThrownBy(() -> writer.write(grouping, many)).isInstanceOf(DataAccessException.class);

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM similarity_group", Integer.class)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM similarity_group_member", Integer.class)).isZero();
	}

	private List<UUID> publicIdsOf(int groupPosition) {
		return jdbcTemplate.queryForList("""
				SELECT m.catalog_file_public_id
				FROM similarity_group_member m
				JOIN similarity_group g ON g.id = m.group_id
				WHERE g.position = ?
				ORDER BY m.position
				""", UUID.class, groupPosition);
	}

	private List<UUID> idsOf(AnalyzedGroup group) {
		return group.members().stream().map(AnalyzedMember::mediaPublicId).toList();
	}

	/** A member with no public id, which the column refuses. */
	private AnalyzedGroup unwritable() {
		return new AnalyzedGroup(90, 1L, List.of(new AnalyzedMember(UUID.randomUUID(), Verdict.KEEP, Reason.ORIGINAL),
				new AnalyzedMember(null, Verdict.DELETE_CANDIDATE, Reason.DERIVATIVE)));
	}

	private AnalyzedGroup group(int similarityPercent, long wastedBytes, int members) {
		List<AnalyzedMember> analyzed = new ArrayList<>(members);

		analyzed.add(new AnalyzedMember(UUID.randomUUID(), Verdict.KEEP, Reason.ORIGINAL));

		for (int index = 1; index < members; index++) {
			analyzed.add(new AnalyzedMember(UUID.randomUUID(), Verdict.DELETE_CANDIDATE, Reason.IDENTICAL_COPY));
		}

		return new AnalyzedGroup(similarityPercent, wastedBytes, analyzed);
	}
}