package br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.RelationParameters;
import lombok.extern.slf4j.Slf4j;

/**
 * Writes approved relations in batches.
 *
 * <p>
 * A whole-library analysis approves tens of thousands of pairs - 31.747 at SSIM
 * 95 on a real library - and saving them one entity at a time would be that many
 * round trips and that many managed objects for rows that are three numbers
 * each. This is the set-based case the repository rules allow a JDBC writer for.
 *
 * <p>
 * <b>Upsert rather than insert.</b> A rebuild re-approves pairs it approved
 * before, and the key is the same by construction, so plain inserts would raise
 * a constraint violation as the ordinary outcome - and Hibernate logs every
 * SQLException at error level on its way out, which is how a healthy run starts
 * writing errors to the log. {@code ON CONFLICT DO UPDATE} makes the second
 * write a no-op with the current score, and stays correct when two runs race.
 *
 * <p>
 * The score can genuinely change for an unchanged key, which is why the conflict
 * updates rather than doing nothing: a file that is edited keeps its catalog id
 * and gets a new fingerprint, so the pair is the same pair and the answer is
 * not.
 */
@Slf4j
@Repository
public class SimilarityRelationWriter {

	/**
	 * Rows per round trip. Large enough that the trips stop mattering, small enough
	 * that a failure does not roll back an hour of work.
	 */
	private static final int BATCH_SIZE = 1000;

	private static final String COVER = """
			INSERT INTO similarity_relation_coverage (algorithm_id, max_distance, min_similarity, relation_digest,
				catalog_file_id, covered_at)
			VALUES (?, ?, ?, ?, ?, ?)
			ON CONFLICT (algorithm_id, max_distance, min_similarity, relation_digest, catalog_file_id) DO NOTHING
			""";

	private static final String UPSERT = """
			INSERT INTO similarity_relation (algorithm_id, max_distance, min_similarity, relation_digest,
				first_catalog_file_id, second_catalog_file_id, similarity_percent, computed_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (algorithm_id, max_distance, min_similarity, relation_digest, first_catalog_file_id,
				second_catalog_file_id)
			DO UPDATE SET similarity_percent = EXCLUDED.similarity_percent, computed_at = EXCLUDED.computed_at
			""";

	private final NamedParameterJdbcTemplate jdbcTemplate;
	private final Clock clock;

	public SimilarityRelationWriter(NamedParameterJdbcTemplate jdbcTemplate, Clock clock) {
		this.jdbcTemplate = jdbcTemplate;
		this.clock = clock;
	}

	/**
	 * Replaces every relation of this parameter set with the ones just computed.
	 *
	 * <p>
	 * Replace and not merge, and the difference is a correctness one. Only
	 * approvals are stored, so a pair that stops being approved does not appear in
	 * the new batch - it simply is not there. An upsert would leave the old row
	 * behind, and that row would go on being read as an approval it no longer is:
	 * a file edited in place keeps its catalog id, scores 60 against a neighbour
	 * it used to score 97 against, and the two would still be grouped together
	 * forever.
	 *
	 * <p>
	 * A rebuild recomputes the whole set for these parameters, so the whole set is
	 * what it may replace. One delete and the inserts, in one transaction: a reader
	 * sees the previous set or the new one, never a gap. The upsert below stays for
	 * the writers that add to an existing set rather than replacing it, and for the
	 * case two runs race.
	 *
	 * @param first catalog ids, one per relation
	 * @param second the other id of each relation, positionally aligned
	 * @param scores the SSIM of each relation, positionally aligned
	 * @param count how many of the arrays are filled - they are grown by doubling
	 * and are usually longer than the data
	 * @return how many rows were written
	 */
	@Transactional
	public int replaceAll(RelationParameters parameters, int[] first, int[] second, int[] scores, int count,
			long[] catalogFileIds) {
		jdbcTemplate.getJdbcTemplate().update("""
				DELETE FROM similarity_relation
				WHERE algorithm_id = ? AND max_distance = ? AND min_similarity = ? AND relation_digest = ?
				""", parameters.algorithmId(), parameters.maxDistance(), parameters.minSimilarity(),
				parameters.relationDigest());

		jdbcTemplate.getJdbcTemplate().update("""
				DELETE FROM similarity_relation_coverage
				WHERE algorithm_id = ? AND max_distance = ? AND min_similarity = ? AND relation_digest = ?
				""", parameters.algorithmId(), parameters.maxDistance(), parameters.minSimilarity(),
				parameters.relationDigest());

		int written = upsertAll(parameters, first, second, scores, count, catalogFileIds);

		cover(parameters, catalogFileIds);

		return written;
	}

	/**
	 * Adds relations to whatever is already stored for these parameters, and
	 * records which files that makes part of the universe. Used when the set is
	 * being extended rather than recomputed - a file arriving brings its own
	 * relations and invalidates none.
	 *
	 * <p>
	 * The two writes are one transaction on purpose, and the order inside it is
	 * the safe one. Coverage claims that every pair between a file and every other
	 * covered file has been evaluated; granting it before the relations are stored
	 * would turn a crash into a permanent silent gap, because the next run would
	 * see the file as already incorporated and never compare it again. The other
	 * way round is merely wasteful: the file stays new, and the retry upserts the
	 * same rows over themselves.
	 *
	 * @param first catalog ids, one per relation
	 * @param second the other id of each relation, positionally aligned
	 * @param scores the SSIM of each relation, positionally aligned
	 * @param count how many of the arrays are filled - they are grown by doubling
	 * and are usually longer than the data
	 * @param catalogFileIds the id of every file this run had in hand, which is
	 * what {@code first} and {@code second} index into
	 * @param newlyCovered the files this run incorporated, which is a subset: the
	 * ones it compared against everything already covered
	 * @return how many relation rows were written
	 */
	@Transactional
	public int save(RelationParameters parameters, int[] first, int[] second, int[] scores, int count,
			long[] catalogFileIds, long[] newlyCovered) {
		int written = upsertAll(parameters, first, second, scores, count, catalogFileIds);

		cover(parameters, newlyCovered);

		return written;
	}

	/**
	 * Marks files as part of the universe, ignoring the ones already marked. A run
	 * that crashed and is being repeated re-states what it already knew rather
	 * than failing on it.
	 */
	private void cover(RelationParameters parameters, long[] catalogFileIds) {
		LocalDateTime now = LocalDateTime.now(clock);

		for (int offset = 0; offset < catalogFileIds.length; offset += BATCH_SIZE) {
			int size = Math.min(BATCH_SIZE, catalogFileIds.length - offset);
			int start = offset;

			jdbcTemplate.getJdbcTemplate().batchUpdate(COVER, new BatchPreparedStatementSetter() {

				@Override
				public void setValues(PreparedStatement statement, int index) throws SQLException {
					statement.setString(1, parameters.algorithmId());
					statement.setInt(2, parameters.maxDistance());
					statement.setInt(3, parameters.minSimilarity());
					statement.setString(4, parameters.relationDigest());
					statement.setLong(5, catalogFileIds[start + index]);
					statement.setTimestamp(6, Timestamp.valueOf(now));
				}

				@Override
				public int getBatchSize() {
					return size;
				}
			});
		}
	}

	/**
	 * The batching itself, with no transaction of its own.
	 *
	 * <p>
	 * {@link #replaceAll} used to reach it by calling {@link #save}, and that was a
	 * transaction boundary that only appeared to exist: a call from inside the same
	 * object never passes through the Spring proxy, so the annotation on
	 * {@code save} did nothing there. It happened to be harmless - the caller was
	 * already transactional and that is exactly the behaviour wanted, one delete and
	 * every insert in one unit - but a guarantee that holds by accident holds until
	 * somebody moves the call.
	 *
	 * <p>
	 * So the shared work lives here, unannotated, and each public entry point
	 * declares the transaction it actually wants. Nothing is inherited from a
	 * neighbour.
	 */
	private int upsertAll(RelationParameters parameters, int[] first, int[] second, int[] scores, int count,
			long[] catalogFileIds) {
		LocalDateTime now = LocalDateTime.now(clock);

		int written = 0;

		for (int offset = 0; offset < count; offset += BATCH_SIZE) {
			int size = Math.min(BATCH_SIZE, count - offset);
			int start = offset;

			int[] applied = jdbcTemplate.getJdbcTemplate().batchUpdate(UPSERT, new BatchPreparedStatementSetter() {

				@Override
				public void setValues(PreparedStatement statement, int index) throws SQLException {
					bind(statement, parameters, catalogFileIds[first[start + index]],
							catalogFileIds[second[start + index]], scores[start + index], now);
				}

				@Override
				public int getBatchSize() {
					return size;
				}
			});

			written += applied.length;
		}

		return written;
	}

	/**
	 * Forgets everything computed from a file's previous fingerprint, and the
	 * claim that the file was ever incorporated.
	 *
	 * <p>
	 * An edited file keeps its catalog id and gets a new fingerprint, so every
	 * relation it took part in was computed from an image that no longer exists -
	 * including the ones that will not be approved again, which no recomputation
	 * can overwrite because they will not be there. Deleting first is the only way
	 * the disappearance of a relation is recorded.
	 *
	 * <p>
	 * The coverage goes with them, and that is what makes the recomputation happen
	 * at all: coverage is the record of which files an incremental run may skip, so
	 * a file whose relations were forgotten while its coverage stayed would never
	 * be compared again. Without the row it simply re-enters as new and is measured
	 * against the whole covered set.
	 *
	 * <p>
	 * Every parameter family of that algorithm at once, deliberately: the new
	 * image is a new image under every threshold and every set of medium
	 * parameters, not only under the ones that happen to be current.
	 *
	 * <p>
	 * <b>Scoped to one algorithm, and that scope is a correctness rule rather
	 * than a tidiness one.</b> A file has a fingerprint per medium, and what
	 * invalidates one does not invalidate the other: a video whose duration was
	 * re-read has to lose its video relations, and a photo sharing that catalog row
	 * - a still exported beside it, a file re-typed by a rebuild - must keep
	 * relations that nothing touched. Forgetting by file alone would silently cost
	 * the other medium a full recomputation, and coverage being deleted with it
	 * would make that recomputation compulsory rather than merely wasteful.
	 *
	 * @return how many relations were forgotten
	 */
	@Transactional
	public int forget(String algorithmId, Long... catalogFileIds) {
		if (catalogFileIds.length == 0) {
			return 0;
		}

		int forgotten = jdbcTemplate.getJdbcTemplate().update("""
				DELETE FROM similarity_relation
				WHERE algorithm_id = ?
				  AND (first_catalog_file_id = ANY(?) OR second_catalog_file_id = ANY(?))
				""", statement -> {
			Array ids = statement.getConnection().createArrayOf("bigint", catalogFileIds);

			statement.setString(1, algorithmId);
			statement.setArray(2, ids);
			statement.setArray(3, ids);
		});

		jdbcTemplate.getJdbcTemplate().update("""
				DELETE FROM similarity_relation_coverage
				WHERE algorithm_id = ? AND catalog_file_id = ANY(?)
				""", statement -> {
			statement.setString(1, algorithmId);
			statement.setArray(2, statement.getConnection().createArrayOf("bigint", catalogFileIds));
		});

		return forgotten;
	}

	/**
	 * The pair is ordered here rather than trusted from the caller: the database
	 * refuses the reversed spelling, and letting that refusal be the discovery
	 * would make a constraint violation part of the normal path.
	 */
	private void bind(PreparedStatement statement, RelationParameters parameters, long left, long right, int score,
			LocalDateTime now) throws SQLException {
		statement.setString(1, parameters.algorithmId());
		statement.setInt(2, parameters.maxDistance());
		statement.setInt(3, parameters.minSimilarity());
		statement.setString(4, parameters.relationDigest());
		statement.setLong(5, Math.min(left, right));
		statement.setLong(6, Math.max(left, right));
		statement.setInt(7, score);
		statement.setTimestamp(8, Timestamp.valueOf(now));
	}
}