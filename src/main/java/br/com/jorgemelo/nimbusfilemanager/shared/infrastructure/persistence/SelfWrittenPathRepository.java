package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.persistence;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * separately, and the code that reads them keeps only the path - and a long
 * write spreads them over successive polls. Consuming the first one would leave
 * every later notification looking foreign, which is the burst of full
 * inventories this exists to prevent.
 */
@Repository
public class SelfWrittenPathRepository {

	private static final String ANNOUNCE = """
			INSERT INTO self_written_path (path_key, execution_id, announced_at)
			VALUES (:pathKey, :executionId, :announcedAt)
			ON CONFLICT (path_key) DO UPDATE
			   SET announced_at = EXCLUDED.announced_at, execution_id = EXCLUDED.execution_id
			""";

	/**
	 * Recent, or still being written by an execution that demonstrably holds its
	 * paths. The second half is what a single very large move across volumes needs:
	 * it can outlast the ceiling on its own, and its notifications keep arriving
	 * the whole time. Bounded by the lease rather than by the status, so a worker
	 * that died cannot leave a path silenced - the lease stops being renewed and
	 * the ceiling applies again.
	 */
	private static final String ANNOUNCED_AMONG = """
			SELECT s.path_key FROM self_written_path s
			 WHERE s.path_key IN (:pathKeys)
			   AND (s.announced_at > :notBefore
			        OR EXISTS (SELECT 1 FROM execution e
			                    WHERE e.id = s.execution_id AND e.status = 'RUNNING' AND e.lease_until > :now))
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
	 * Records a path this product is about to write.
	 *
	 * <p>
	 * Announcing again renews it, which is what a write that goes on for minutes
	 * needs: each further announcement pushes the expiry out, so the notifications
	 * still arriving are still recognised.
	 *
	 * @param executionId the execution this write belongs to, or {@code null} for
	 * a write nobody queued - an Explorer rename, a folder swept after organising
	 */
	public void announce(String pathKey, Long executionId, LocalDateTime announcedAt) {
		jdbcTemplate.update(ANNOUNCE, namedParameters(pathKey, executionId, announcedAt));
	}

	/**
	 * Which of these paths this product announced and has not let expire.
	 *
	 * <p>
	 * Asked about the whole poll at once: the watcher hands over everything it saw
	 * this round, and one question is one round trip whatever the answer.
	 */
	public Set<String> announcedAmong(Collection<String> pathKeys, LocalDateTime notBefore, LocalDateTime now) {
		if (pathKeys.isEmpty()) {
			return Set.of();
		}

		List<String> announced = jdbcTemplate.queryForList(ANNOUNCED_AMONG,
				Map.of("pathKeys", pathKeys, "notBefore", notBefore, "now", now), String.class);

		return Set.copyOf(announced);
	}

	/**
	 * Drops what nobody claimed in time. Housekeeping only - what makes an old
	 * entry harmless is the age filter on the question, not this.
	 *
	 * @return how many were dropped
	 */
	public int deleteExpired(LocalDateTime expiredBefore, LocalDateTime now) {
		return jdbcTemplate.update(DELETE_EXPIRED, Map.of("expiredBefore", expiredBefore, "now", now));
	}

	/**
	 * Built by hand because a write nobody queued has a null execution id, and
	 * {@code Map.of} refuses one.
	 */
	private Map<String, Object> namedParameters(String pathKey, Long executionId, LocalDateTime announcedAt) {
		Map<String, Object> parameters = HashMap.newHashMap(3);

		parameters.put("pathKey", pathKey);
		parameters.put("executionId", executionId);
		parameters.put("announcedAt", announcedAt);

		return parameters;
	}
}