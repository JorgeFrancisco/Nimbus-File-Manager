package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The paths this product is writing, shared by every process that writes them.
 *
 * <p>
 * A table rather than a map because the writing and the watching stopped being
 * the same process. The worker moves a file and the application's watcher sees
 * it arrive; memory in either one answers only for itself, and the answer the
 * watcher needs is about a write it did not make.
 *
 * <p>
 * Announced rows are <em>looked at</em>, never taken. One write produces several
 * notifications - Windows reports the name, the size and the last write
 * separately - and a long write spreads them over successive polls. Consuming
 * the first one would leave every later notification looking foreign, which is
 * the burst of full inventories this exists to prevent.
 *
 * <p>
 * <b>Every statement here compares canonical spellings, and none of them
 * computes one.</b> {@code canonicalize_catalog_path} is what the catalog keys
 * its locations by, so the watcher asking "did we write this" and the catalog
 * asking "is this the file I know" cannot answer differently - which they did,
 * on POSIX, for as long as the key was a lowercased path.
 */
@Repository
public class SelfWrittenPathRepository {

	/**
	 * Distinct by key and role because a case-only rename on Windows announces
	 * two spellings of one place, and a conflicting insert may not touch the same
	 * row twice. Which of the two survives is immaterial: they are the same row.
	 */
	private static final String ANNOUNCE = """
			INSERT INTO self_written_path (announced_path, path_flavor, role, execution_id, announced_at)
			SELECT DISTINCT ON (canonicalize_catalog_path(q.path, q.flavor), q.role)
				   q.path, q.flavor, q.role, :executionId, :announcedAt
			  FROM unnest(CAST(:paths AS text[]), CAST(:flavors AS text[]), CAST(:roles AS text[]))
				   AS q(path, flavor, role)
			ON CONFLICT (path_key, role) DO UPDATE
			   SET announced_path = EXCLUDED.announced_path, path_flavor = EXCLUDED.path_flavor,
				   execution_id = EXCLUDED.execution_id, announced_at = EXCLUDED.announced_at
			""";

	/**
	 * Recent, or still being written by an execution that demonstrably holds its
	 * paths. The second half is what a single very large move across volumes
	 * needs: it can outlast the ceiling on its own, and its notifications keep
	 * arriving the whole time. Bounded by the lease rather than by the status, so
	 * a worker that died cannot leave a path silenced - the lease stops being
	 * renewed and the ceiling applies again.
	 *
	 * <p>
	 * Answers with the position each claim held in the question. A path can be
	 * asked about under both roles in one round - the destination of one move is
	 * the source of the next - so neither the path nor the key identifies which
	 * claim was answered, and the position does.
	 */
	private static final String ANNOUNCED_AMONG = """
			SELECT q.position
			  FROM unnest(CAST(:paths AS text[]), CAST(:flavors AS text[]), CAST(:roles AS text[]))
				   WITH ORDINALITY AS q(path, flavor, role, position)
			  JOIN self_written_path s ON s.path_key = canonicalize_catalog_path(q.path, q.flavor)
									  AND s.role = q.role
			 WHERE s.announced_at > :notBefore
				OR EXISTS (SELECT 1 FROM execution e
							WHERE e.id = s.execution_id AND e.status = 'RUNNING' AND e.lease_until > :now)
			""";

	/**
	 * The write finished: the ceiling restarts from now and the entry stops being
	 * held alive by its execution.
	 *
	 * <p>
	 * Guarded by the announcement it belongs to. A row announced again after ours
	 * - another operation on the same path, which the operation lock makes rare
	 * but not impossible - is that operation's to close, and settling it here
	 * would shorten a window somebody else is still relying on.
	 */
	private static final String SETTLE = """
			UPDATE self_written_path s
			   SET announced_at = :now, execution_id = NULL
			  FROM unnest(CAST(:paths AS text[]), CAST(:flavors AS text[]), CAST(:roles AS text[]))
				   AS q(path, flavor, role)
			 WHERE s.path_key = canonicalize_catalog_path(q.path, q.flavor)
			   AND s.role = q.role
			   AND s.announced_at <= :announcedAt
			""";

	/** The write never happened, so nothing of ours is coming. Same guard. */
	private static final String REVOKE = """
			DELETE FROM self_written_path s
			 USING unnest(CAST(:paths AS text[]), CAST(:flavors AS text[]), CAST(:roles AS text[]))
				   AS q(path, flavor, role)
			 WHERE s.path_key = canonicalize_catalog_path(q.path, q.flavor)
			   AND s.role = q.role
			   AND s.announced_at <= :announcedAt
			""";

	/**
	 * Sweeps what nobody can still be writing. The same lease condition guards it,
	 * so housekeeping can never remove a row the question above would have
	 * accepted.
	 */
	private static final String DELETE_EXPIRED = """
			DELETE FROM self_written_path s
			 WHERE s.announced_at <= :expiredBefore
			   AND NOT EXISTS (SELECT 1 FROM execution e
								WHERE e.id = s.execution_id AND e.status = 'RUNNING' AND e.lease_until > :now)
			""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public SelfWrittenPathRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Records paths this product is about to write.
	 *
	 * <p>
	 * Announcing again renews the entry, which is what a write that goes on for
	 * minutes needs: each further announcement pushes the expiry out, so the
	 * notifications still arriving are still recognised.
	 *
	 * @param executionId the execution this write belongs to, or {@code null} for
	 * a write nobody queued - an Explorer rename, a folder swept after organising
	 */
	public void announce(String[] paths, String[] flavors, String[] roles, Long executionId,
			LocalDateTime announcedAt) {
		jdbcTemplate.update(ANNOUNCE, arrays(paths, flavors, roles).addValue("executionId", executionId)
				.addValue("announcedAt", announcedAt));
	}

	/**
	 * Which of these claims this product announced and has not let expire, by the
	 * position each held in the question.
	 */
	public Set<Integer> announcedAmong(String[] paths, String[] flavors, String[] roles, LocalDateTime notBefore,
			LocalDateTime now) {
		List<Integer> announced = jdbcTemplate.queryForList(ANNOUNCED_AMONG,
				arrays(paths, flavors, roles).addValue("notBefore", notBefore).addValue("now", now), Integer.class);

		return Set.copyOf(announced);
	}

	/** @return how many entries were still ours to close */
	public int settle(String[] paths, String[] flavors, String[] roles, LocalDateTime announcedAt,
			LocalDateTime now) {
		return jdbcTemplate.update(SETTLE,
				arrays(paths, flavors, roles).addValue("announcedAt", announcedAt).addValue("now", now));
	}

	/** @return how many entries were still ours to withdraw */
	public int revoke(String[] paths, String[] flavors, String[] roles, LocalDateTime announcedAt) {
		return jdbcTemplate.update(REVOKE, arrays(paths, flavors, roles).addValue("announcedAt", announcedAt));
	}

	/**
	 * Drops what nobody claimed in time. Housekeeping only - what makes an old
	 * entry harmless is the age filter on the question, not this.
	 *
	 * @return how many were dropped
	 */
	public int deleteExpired(LocalDateTime expiredBefore, LocalDateTime now) {
		return jdbcTemplate.update(DELETE_EXPIRED,
				new MapSqlParameterSource().addValue("expiredBefore", expiredBefore).addValue("now", now));
	}

	private static MapSqlParameterSource arrays(String[] paths, String[] flavors, String[] roles) {
		return new MapSqlParameterSource().addValue("paths", paths).addValue("flavors", flavors).addValue("roles",
				roles);
	}
}