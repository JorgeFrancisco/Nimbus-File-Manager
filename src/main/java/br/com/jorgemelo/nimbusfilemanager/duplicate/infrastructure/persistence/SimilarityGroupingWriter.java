package br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedMember;

/**
 * Writes the groups and members of one analysis in batches.
 *
 * <p>
 * <b>Why this exists, measured rather than assumed.</b> Written through JPA the
 * same result took 37,5 s, of which the database accounted for 3,9 s: Hibernate
 * reported 38.113 entity inserts against 38.113 prepared statements - one round
 * trip per row, no batching - and {@code pg_stat_statements} counted the same
 * 38.112 calls. Ninety per cent of the time was spent getting there and back.
 *
 * <p>
 * Batching was impossible rather than merely unconfigured. Every id here is
 * {@code GenerationType.IDENTITY}, so Hibernate has to execute each insert
 * immediately to learn the key before it can hold the entity - which is also
 * why obtaining a group's id cost a round trip per group. This is the set-based
 * case the repository rules allow a JDBC writer for, and the sibling
 * {@code SimilarityRelationWriter} is the precedent: 31.747 rows in 5 s.
 *
 * <p>
 * <b>The ids are reserved instead of returned.</b> Members point at their group,
 * so the group ids have to exist before the members are written; asking the
 * database for them one insert at a time is the cost being removed. One query
 * over the table's own sequence hands over every id at once, and both inserts
 * then go out in batches. Nothing about the generation changes - the sequence is
 * the one {@code BIGSERIAL} created and the entities keep their mapping - only
 * who asks, and how often.
 *
 * <p>
 * Member ids are not reserved, because nothing points at a member: the column
 * default fills them in.
 *
 * <p>
 * No transaction of its own by design. The caller is writing a result that must
 * appear whole or not at all, so this joins that transaction and a failure
 * anywhere takes the grouping with it.
 */
@Repository
public class SimilarityGroupingWriter {

	/**
	 * Rows per round trip. Large enough that the trips stop mattering, small enough
	 * that a failure does not roll back an hour of work - the same figure the
	 * relation writer uses.
	 */
	private static final int BATCH_SIZE = 1000;

	/**
	 * Every id in one round trip. {@code nextval} is volatile, so it is evaluated
	 * once per generated row, and the sequence is named through
	 * {@code pg_get_serial_sequence} rather than spelled out - the name belongs to
	 * the column definition, and repeating it here would be a second place to get
	 * it wrong.
	 */
	private static final String RESERVE_GROUP_IDS = """
			SELECT nextval(pg_get_serial_sequence('similarity_group', 'id'))
			FROM generate_series(1, ?)
			""";

	private static final String INSERT_GROUP = """
			INSERT INTO similarity_group (id, grouping_id, similarity_percent, file_count, wasted_bytes, position)
			VALUES (?, ?, ?, ?, ?, ?)
			""";

	private static final String INSERT_MEMBER = """
			INSERT INTO similarity_group_member (group_id, media_public_id, verdict, reason, position)
			VALUES (?, ?, ?, ?, ?)
			""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public SimilarityGroupingWriter(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * @param groupingId the header row, already inserted, that these groups belong
	 * to
	 * @param groups in the order they are to be shown; the position of each group
	 * and of each member inside it is its index, which is what the screen reads
	 * them back by
	 */
	@Transactional
	public void write(Long groupingId, List<AnalyzedGroup> groups) {
		if (groups.isEmpty()) {
			return;
		}

		long[] groupIds = reserveGroupIds(groups.size());

		insertGroups(groupingId, groupIds, groups);
		insertMembers(groupIds, groups);
	}

	private long[] reserveGroupIds(int count) {
		List<Long> reserved = jdbcTemplate.getJdbcTemplate().queryForList(RESERVE_GROUP_IDS, Long.class, count);

		long[] ids = new long[reserved.size()];

		for (int index = 0; index < ids.length; index++) {
			ids[index] = reserved.get(index);
		}

		return ids;
	}

	private void insertGroups(Long groupingId, long[] groupIds, List<AnalyzedGroup> groups) {
		for (int offset = 0; offset < groups.size(); offset += BATCH_SIZE) {
			int size = Math.min(BATCH_SIZE, groups.size() - offset);
			int start = offset;

			jdbcTemplate.getJdbcTemplate().batchUpdate(INSERT_GROUP, new BatchPreparedStatementSetter() {

				@Override
				public void setValues(PreparedStatement statement, int index) throws SQLException {
					AnalyzedGroup group = groups.get(start + index);

					statement.setLong(1, groupIds[start + index]);
					statement.setLong(2, groupingId);
					statement.setInt(3, group.similarityPercent());
					statement.setInt(4, group.members().size());
					statement.setLong(5, group.wastedBytes());
					statement.setInt(6, start + index);
				}

				@Override
				public int getBatchSize() {
					return size;
				}
			});
		}
	}

	/**
	 * Flattened first, batched second. A batch crosses group boundaries, so the
	 * writer needs to answer "which group and which position is the n-th member" in
	 * constant time; one pass that lays the members out in order, remembering the
	 * group id and the position of each, is cheaper to run and far easier to read
	 * than arithmetic over group sizes inside the binding.
	 */
	private void insertMembers(long[] groupIds, List<AnalyzedGroup> groups) {
		List<AnalyzedMember> members = new ArrayList<>();
		List<Long> owners = new ArrayList<>();
		List<Integer> positions = new ArrayList<>();

		for (int group = 0; group < groups.size(); group++) {
			List<AnalyzedMember> inGroup = groups.get(group).members();

			for (int position = 0; position < inGroup.size(); position++) {
				members.add(inGroup.get(position));
				owners.add(groupIds[group]);
				positions.add(position);
			}
		}

		for (int offset = 0; offset < members.size(); offset += BATCH_SIZE) {
			int size = Math.min(BATCH_SIZE, members.size() - offset);
			int start = offset;

			jdbcTemplate.getJdbcTemplate().batchUpdate(INSERT_MEMBER, new BatchPreparedStatementSetter() {

				@Override
				public void setValues(PreparedStatement statement, int index) throws SQLException {
					AnalyzedMember member = members.get(start + index);

					statement.setLong(1, owners.get(start + index));
					statement.setObject(2, member.mediaPublicId());
					statement.setString(3, member.verdict().name());
					statement.setString(4, member.reason() == null ? null : member.reason().name());
					statement.setInt(5, positions.get(start + index));
				}

				@Override
				public int getBatchSize() {
					return size;
				}
			});
		}
	}
}