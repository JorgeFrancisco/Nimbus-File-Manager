package br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;

/**
 * The work queue, as rows of {@code execution}.
 *
 * <p>
 * Taking a job is two steps on purpose, and they cannot be merged. The
 * reservation is a short transaction that flips one PENDING row to RUNNING and
 * commits - the row lock lives for milliseconds, never for the hours the work
 * itself may take. Only afterwards does the caller acquire the path locks,
 * which live in another connection entirely and are invisible to any SELECT
 * here. That is why the query filters on status, availability and attempts, and
 * says nothing about paths: it can only filter on what it can see.
 *
 * <p>
 * {@code claim_count} is not touched by the reservation. It counts attempts
 * that got as far as owning their resources and starting for real, so it moves
 * in {@link #countAttempt}, after the locks are held - and it never moves back.
 * A path that happens to be busy is not a failed attempt, and charging one
 * would spend the poison-job budget on contention.
 */
@Repository
public class ExecutionQueue {

	/**
	 * Waiting time counts toward priority, an hour per point, capped at five.
	 * Without it a maintenance pass could sit behind interactive work
	 * indefinitely. The expression cannot be served by an index, which is
	 * affordable because the pending set is dozens of rows - the partial index
	 * keeps the scan off the history, which is what actually grows.
	 *
	 * <p>
	 * The {@code NOT EXISTS} is the other half of the 1 + 1 rule. One request may
	 * wait while an identical one runs, but the waiting one cannot be claimed
	 * while the running one is still running: taking it writes RUNNING a second
	 * time for the same key, which the partial unique index refuses. Left to the
	 * index alone, the refusal arrives as an integrity violation raised from the
	 * claim itself - so the loop logs an error, finds the same pair on the next
	 * round and logs it again, for as long as the running row stays running. It
	 * is stated here because it is a thing the query can see; the index goes on
	 * being what makes it true.
	 */
	private static final String RESERVE = """
			UPDATE execution
			   SET status = 'RUNNING', claimed_by = :workerId, claimed_at = :now, lease_until = :leaseUntil,
			       started_at = COALESCE(started_at, :now)
			 WHERE id = (SELECT queued.id
			               FROM execution queued
			              WHERE queued.status = 'PENDING'
			                AND queued.available_at <= :now
			                AND queued.claim_count < :maxClaims
			                AND queued.execution_type = ANY(:types)
			                AND NOT EXISTS (SELECT 1 FROM execution running
			                                 WHERE running.status = 'RUNNING'
			                                   AND running.dedup_key IS NOT NULL
			                                   AND running.dedup_key = queued.dedup_key
			                                   AND running.execution_type = queued.execution_type)
			              ORDER BY queued.priority
			                     + LEAST(EXTRACT(EPOCH FROM (now() - queued.created_at)) / 3600, 5) DESC, queued.id
			              FOR UPDATE SKIP LOCKED
			              LIMIT 1)
			RETURNING id, execution_type, source_path, target_path, request_payload
			""";

	/**
	 * The guard that actually enforces the poison-job limit. The reservation read
	 * {@code claim_count} milliseconds earlier and the locks were acquired since,
	 * so that read is stale by definition; this re-states the limit, the ownership
	 * and the state in one atomic write, and the caller runs nothing unless
	 * exactly one row was affected.
	 */
	private static final String COUNT_ATTEMPT = """
			UPDATE execution
			   SET claim_count = claim_count + 1
			 WHERE id = :id AND claimed_by = :workerId AND status = 'RUNNING' AND claim_count < :maxClaims
			""";

	/**
	 * Handing a job back because its paths were busy. Everything the reservation
	 * wrote is undone and the row becomes available again after the backoff the
	 * worker was configured with - {@code claim_count} deliberately untouched.
	 *
	 * <p>
	 * The guard is the {@code NOT EXISTS}. Two rows of the same request may be
	 * waiting and running at once - that is the design - but two may not be
	 * waiting, and handing a running one back while its successor is already
	 * queued would be exactly that: a violation of the partial unique index,
	 * raised from inside the code path that was recovering from something else.
	 */
	private static final String RELEASE = """
			UPDATE execution
			   SET status = 'PENDING', claimed_by = NULL, claimed_at = NULL, lease_until = NULL,
			       started_at = NULL, available_at = :availableAt
			 WHERE id = :id AND claimed_by = :workerId AND status = 'RUNNING'
			   AND NOT EXISTS (SELECT 1 FROM execution waiting
			                    WHERE waiting.status = 'PENDING' AND waiting.dedup_key IS NOT NULL
			                      AND waiting.dedup_key = execution.dedup_key
			                      AND waiting.execution_type = execution.execution_type)
			""";

	private static final String REQUEUE = """
			UPDATE execution
			   SET status = 'PENDING', claimed_by = NULL, claimed_at = NULL, lease_until = NULL,
			       started_at = NULL, available_at = :availableAt
			 WHERE id = :id AND status = 'RUNNING'
			   AND NOT EXISTS (SELECT 1 FROM execution waiting
			                    WHERE waiting.status = 'PENDING' AND waiting.dedup_key IS NOT NULL
			                      AND waiting.dedup_key = execution.dedup_key
			                      AND waiting.execution_type = execution.execution_type)
			""";

	private static final String HAS_WAITING_DUPLICATE = """
			SELECT EXISTS (SELECT 1 FROM execution waiting
			                WHERE waiting.status = 'PENDING' AND waiting.dedup_key IS NOT NULL
			                  AND waiting.id <> :id
			                  AND waiting.dedup_key = (SELECT dedup_key FROM execution WHERE id = :id)
			                  AND waiting.execution_type = (SELECT execution_type FROM execution WHERE id = :id))
			""";

	private static final String RENEW = """
			UPDATE execution
			   SET lease_until = :leaseUntil
			 WHERE claimed_by = :workerId AND status = 'RUNNING' AND id = ANY(:ids)
			""";

	/**
	 * Asking a running execution to stop. Only RUNNING rows: a PENDING one nobody
	 * took is cancelled by finishing it outright, and a finished one has nothing
	 * left to interrupt.
	 */
	private static final String REQUEST_CANCEL = """
			UPDATE execution SET cancel_requested = TRUE WHERE id = :id AND status = 'RUNNING'
			""";

	private static final String REQUEST_CANCEL_ALL_RUNNING = """
			UPDATE execution SET cancel_requested = TRUE WHERE status = 'RUNNING' AND cancel_requested = FALSE
			""";

	private static final String CANCEL_ALL_PENDING = """
			UPDATE execution
			   SET status = 'CANCELLED', finished_at = :now, cancel_requested = TRUE
			 WHERE status = 'PENDING'
			""";

	private static final String CANCEL_REQUESTED = """
			SELECT cancel_requested FROM execution WHERE id = :id
			""";

	/**
	 * Executions whose owner is not renewing.
	 *
	 * <p>
	 * A null lease counts as expired, and that is not a special case for old rows:
	 * a RUNNING execution nobody holds a lease on is by definition unowned, whether
	 * because the lease lapsed or because it was never taken. Both mean the same
	 * thing to recovery.
	 */
	private static final String EXPIRED = """
			SELECT id FROM execution WHERE status = 'RUNNING' AND lease_until < :now
			""";

	private static final String WORKER_ID = "workerId";

	private final NamedParameterJdbcTemplate jdbcTemplate;
	private final Clock clock;

	public ExecutionQueue(NamedParameterJdbcTemplate jdbcTemplate, Clock clock) {
		this.jdbcTemplate = jdbcTemplate;
		this.clock = clock;
	}

	/**
	 * Takes the next eligible job, or returns empty when there is nothing to do.
	 * {@code SKIP LOCKED} is what lets two claimers run against the same queue
	 * without either blocking or ever seeing the same row.
	 */
	public Optional<ClaimedExecution> reserve(String workerId, List<String> types, int maxClaims, int leaseSeconds) {
		LocalDateTime now = LocalDateTime.now(clock);

		MapSqlParameterSource parameters = new MapSqlParameterSource().addValue(WORKER_ID, workerId)
				.addValue("now", now).addValue("leaseUntil", now.plusSeconds(leaseSeconds))
				.addValue("maxClaims", maxClaims).addValue("types", types.toArray(String[]::new));

		List<ClaimedExecution> claimed = jdbcTemplate.query(RESERVE, parameters,
				(rs, _) -> new ClaimedExecution(rs.getLong("id"), rs.getString("execution_type"),
						rs.getString("source_path"), rs.getString("target_path"), rs.getString("request_payload")));

		return claimed.stream().findFirst();
	}

	/**
	 * Records that this attempt is starting for real. Returns false when the row
	 * is no longer ours, no longer RUNNING, or has spent its attempts - in every
	 * one of those cases nothing may run.
	 */
	public boolean countAttempt(long executionId, String workerId, int maxClaims) {
		return jdbcTemplate.update(COUNT_ATTEMPT, Map.of("id", executionId, WORKER_ID, workerId, "maxClaims",
				maxClaims)) == 1;
	}

	public boolean release(long executionId, String workerId, int backoffSeconds) {
		LocalDateTime availableAt = LocalDateTime.now(clock).plusSeconds(backoffSeconds);

		return jdbcTemplate.update(RELEASE,
				Map.of("id", executionId, WORKER_ID, workerId, "availableAt", availableAt)) == 1;
	}

	/**
	 * Renews every lease this worker holds in one statement. One round trip for
	 * all of them, from a thread that does no work of its own - a renewal that
	 * depended on the working thread would lapse whenever that thread sat in
	 * {@code waitFor} on an external tool.
	 */
	public int renewLeases(String workerId, List<Long> executionIds, int leaseSeconds) {
		if (executionIds.isEmpty()) {
			return 0;
		}

		MapSqlParameterSource parameters = new MapSqlParameterSource().addValue(WORKER_ID, workerId)
				.addValue("leaseUntil", LocalDateTime.now(clock).plusSeconds(leaseSeconds))
				.addValue("ids", executionIds.toArray(Long[]::new));

		return jdbcTemplate.update(RENEW, parameters);
	}


	/**
	 * Records that the user asked this execution to stop. Persisted rather than
	 * flagged in memory, because whoever is running the work may be another
	 * process entirely - and because a request that survives nothing is not a
	 * request.
	 *
	 * @return false when there was nothing running to cancel
	 */
	public boolean requestCancel(long executionId) {
		return jdbcTemplate.update(REQUEST_CANCEL, Map.of("id", executionId)) == 1;
	}

	/**
	 * Asks everything in flight, anywhere, to stop.
	 *
	 * <p>
	 * Both halves matter, and only one of them existed. A RUNNING execution is
	 * asked - it is the worker that decides where stopping is safe. A PENDING one
	 * is ended outright, because nobody has started it and leaving it would mean a
	 * worker taking it up the moment the asking was over, which is precisely what
	 * an administrative operation is waiting for not to happen.
	 *
	 * <p>
	 * Written in the database rather than in a set in memory, so that "everything"
	 * means every process rather than this one.
	 *
	 * @return how many rows were asked or ended
	 */
	public int requestCancelOfEverything() {
		int running = jdbcTemplate.update(REQUEST_CANCEL_ALL_RUNNING, Map.of());

		int pending = jdbcTemplate.update(CANCEL_ALL_PENDING, Map.of("now", LocalDateTime.now(clock)));

		return running + pending;
	}

	public boolean isCancelRequested(long executionId) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject(CANCEL_REQUESTED, Map.of("id", executionId),
				Boolean.class));
	}

	/**
	 * Puts an abandoned execution back on the queue, without asking who owned it.
	 *
	 * <p>
	 * Deliberately not {@code release}, which requires the caller to be the owner:
	 * the owner here is a worker that is gone, and demanding its name would mean
	 * nobody could ever take the work back.
	 *
	 * @return true when the row was still RUNNING and is now waiting again
	 */
	public boolean requeue(long executionId) {
		return jdbcTemplate.update(REQUEUE,
				Map.of("id", executionId, "availableAt", LocalDateTime.now(clock))) == 1;
	}

	/**
	 * Whether an identical request is already waiting.
	 *
	 * <p>
	 * Asked only after a release or a requeue did nothing, to tell the two reasons
	 * apart: the row moved on under us, or there is a successor and this one has
	 * become redundant. The answers deserve different outcomes, and a row count of
	 * zero does not distinguish them.
	 */
	public boolean hasWaitingDuplicate(long executionId) {
		return Boolean.TRUE.equals(
				jdbcTemplate.queryForObject(HAS_WAITING_DUPLICATE, Map.of("id", executionId), Boolean.class));
	}

	/**
	 * Jobs whose owner stopped renewing - a worker that was killed, or one that
	 * lost the database. Recovery decides what each one deserves; the queue only
	 * says which they are.
	 *
	 * <p>
	 * This is the whole of "abandoned". A row reaches RUNNING only through the
	 * claim above, which writes the lease in the same statement, so RUNNING without
	 * a lease is a state the queue cannot produce - and the question "is anybody
	 * still working on this?" has exactly one answer, readable by any process.
	 */
	public List<Long> expiredLeases() {
		return jdbcTemplate.queryForList(EXPIRED, Map.of("now", LocalDateTime.now(clock)), Long.class);
	}
}