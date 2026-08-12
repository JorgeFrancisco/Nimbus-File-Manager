package br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence;

import java.sql.Types;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionPossession;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;

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
	 * The age is measured against {@code :now} - the application's clock, the same
	 * one the rest of this statement uses and the same one that wrote
	 * {@code created_at}. It used to be measured against the database's
	 * {@code now()}, and those are not the same instant expressed two ways: the
	 * column is a {@code timestamp without time zone} holding local time in the
	 * <em>configured</em> zone, while {@code now()} is rendered in the
	 * <em>session</em> zone, which the JDBC driver takes from the client JVM.
	 * Wherever the two zones differ, every pending row was credited with the
	 * offset as waiting it had not done - and the cap turned that into an
	 * inversion, because a row already at five gains nothing from the phantom
	 * hours while a young one pockets all of them. Measured on a UTC machine
	 * against a Sao Paulo setting, a brand new request at priority two scored
	 * 5.0000083 against 5.0 for one that had genuinely waited five hours.
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
			                     + LEAST(EXTRACT(EPOCH FROM (CAST(:now AS timestamp) - queued.created_at))
			                             / 3600, 5) DESC, queued.id
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
			RETURNING claim_count
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
			   AND (CAST(:claimCount AS INTEGER) IS NULL OR claim_count = :claimCount)
			   AND NOT EXISTS (SELECT 1 FROM execution waiting
			                    WHERE waiting.status = 'PENDING' AND waiting.dedup_key IS NOT NULL
			                      AND waiting.dedup_key = execution.dedup_key
			                      AND waiting.execution_type = execution.execution_type)
			""";

	/**
	 * The lease predicate is what makes recovery safe to run while the system is
	 * working, and it is not the same question the caller already asked.
	 *
	 * <p>
	 * Recovery reads the abandoned rows and then acts on them one at a time, so
	 * between the reading and this statement the owner may have renewed - a worker
	 * that was paused, not dead. Without the predicate the requeue would take a
	 * job away from a process that is still running it, and the only reason that
	 * was never seen is that recovery used to run once, at startup, when this
	 * process held nothing. Stated here rather than checked in Java because the
	 * two reclaimers a multi-worker install can have must be arbitrated by
	 * something they share.
	 */
	private static final String REQUEUE = """
			UPDATE execution
			   SET status = 'PENDING', claimed_by = NULL, claimed_at = NULL, lease_until = NULL,
			       started_at = NULL, available_at = :availableAt
			 WHERE id = :id AND status = 'RUNNING' AND lease_until < :now
			   AND NOT EXISTS (SELECT 1 FROM execution waiting
			                    WHERE waiting.status = 'PENDING' AND waiting.dedup_key IS NOT NULL
			                      AND waiting.dedup_key = execution.dedup_key
			                      AND waiting.execution_type = execution.execution_type)
			""";

	/**
	 * Ends an abandoned execution, on the same condition the requeue carries: the
	 * row must still be the running one whose lease has lapsed.
	 *
	 * <p>
	 * Everything the transition means to this row is written here, in one
	 * conditional statement - the outcome, when it ended, the sentence that
	 * explains it, and the per-item progress that no longer applies. It was two
	 * writes once: this one, and then a read-modify-write of the whole entity to
	 * add the message. The second one carried no condition, so it could put back a
	 * row that had moved on in between, and it re-stamped the finish time with a
	 * different instant from the one that had decided the expiry.
	 *
	 * <p>
	 * The same {@code :now} answers both questions on purpose: the moment that
	 * decides whether the lease had run out is the moment recorded as the end.
	 * Two clocks here would be two definitions of when the taking stopped.
	 *
	 * <p>
	 * Zero rows means this pass did not win the recovery, and nothing derived from
	 * it may happen - no sentence, no history, no local cleanup.
	 */
	private static final String END_ABANDONED = """
			UPDATE execution
			   SET status = :status, finished_at = :now,
			       message_code = :messageCode, message_args = :messageArgs, message = NULL,
			       current_item_percent = NULL
			 WHERE id = :id AND status = 'RUNNING' AND lease_until < :now
			""";

	private static final String HAS_WAITING_DUPLICATE = """
			SELECT EXISTS (SELECT 1 FROM execution waiting
			                WHERE waiting.status = 'PENDING' AND waiting.dedup_key IS NOT NULL
			                  AND waiting.id <> :id
			                  AND waiting.dedup_key = (SELECT dedup_key FROM execution WHERE id = :id)
			                  AND waiting.execution_type = (SELECT execution_type FROM execution WHERE id = :id))
			""";

	/**
	 * Renewal is about takings, not about executions.
	 *
	 * <p>
	 * Scoped by attempt number for the same reason everything else here is: two
	 * takings of one row can be live in one process, and a renewal belonging to
	 * the finished one must not extend the running one merely because both were
	 * claimed by the same name. It also makes the answer unambiguous - an id on
	 * its own would not say which taking the database confirmed.
	 *
	 * <p>
	 * The time condition is the same boundary recovery uses, from the other side:
	 * recovery takes {@code lease_until < now}, so a taking may still renew at
	 * {@code lease_until >= now}. A lease that has run out is over for good; going
	 * on would let a late heartbeat revive a taking recovery had already given up
	 * on, and there would again be two answers to who owns the row.
	 *
	 * <p>
	 * One statement for all of them, as before: the pairs travel as two parallel
	 * arrays and are zipped by {@code unnest}, which keeps the round trip single
	 * however many this worker holds.
	 */
	private static final String RENEW = """
			UPDATE execution e
			   SET lease_until = :leaseUntil
			  FROM unnest(CAST(:ids AS BIGINT[]), CAST(:claimCounts AS INTEGER[])) AS taking(id, claim_count)
			 WHERE e.id = taking.id
			   AND e.claim_count = taking.claim_count
			   AND e.claimed_by = :workerId
			   AND e.status = 'RUNNING'
			   AND e.lease_until >= :now
			RETURNING e.id, e.claim_count
			""";

	/**
	 * Holds a taking still in force, for as long as the caller's transaction runs.
	 *
	 * <p>
	 * This is what makes a domain write safe, and a plain read would not do it. A
	 * subquery over this row is evaluated under the statement's snapshot and locks
	 * nothing, so an owner that lost the execution between the snapshot and the
	 * write would still write. {@code FOR SHARE} takes the row: a concurrent
	 * change blocks here and is then re-checked against the version that won, and
	 * a change that arrives afterwards waits for this transaction instead. Either
	 * way there is no instant in which two takings both believe they are current.
	 *
	 * <p>
	 * The time condition is the complement of the one recovery asks - recovery
	 * takes {@code lease_until < now}, so a taking is in force at
	 * {@code lease_until >= now} - written this way on purpose: two definitions of
	 * the same boundary would eventually disagree about one instant, and that
	 * instant is the whole problem. A lease that has run out therefore fails here
	 * before any recovery has run, and fails without taking the lock, so it cannot
	 * stand in the way of the recovery that is coming.
	 *
	 * <p>
	 * Deliberately silent about {@code cancel_requested}: cancelling asks a worker
	 * to wind down and write its own outcome, which is not the same as taking the
	 * execution away from it.
	 */
	private static final String PIN = """
			SELECT 1 FROM execution
			 WHERE id = :id
			   AND status = 'RUNNING'
			   AND claimed_by = :workerId
			   AND claim_count = :claimCount
			   AND lease_until >= :now
			 FOR SHARE
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
	 * The comparison excludes a null lease rather than treating it as expired,
	 * which is the same thing here: a row reaches RUNNING only through the claim,
	 * and the claim writes the lease in the same statement, so RUNNING without one
	 * is a state this queue cannot produce. It is also what lets the recovery
	 * writes carry this predicate unchanged - a condition that has to hold at the
	 * moment of the write, not only at the moment of the read.
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
	 * Records that this attempt is starting for real. Empty when the row is no
	 * longer ours, no longer RUNNING, or has spent its attempts - in every one of
	 * those cases nothing may run.
	 *
	 * @return the attempt number just written, which together with the worker name
	 * identifies this taking of the row for as long as it lasts
	 */
	public OptionalInt countAttempt(long executionId, String workerId, int maxClaims) {
		List<Integer> counted = jdbcTemplate.queryForList(COUNT_ATTEMPT,
				Map.of("id", executionId, WORKER_ID, workerId, "maxClaims", maxClaims), Integer.class);

		return counted.isEmpty() ? OptionalInt.empty() : OptionalInt.of(counted.getFirst());
	}

	/**
	 * Hands the row back, and only for the taking that asked.
	 *
	 * <p>
	 * The attempt number is absent when the hand-back happens before the attempt
	 * was counted - no capacity for the category, or the paths were busy - and
	 * present once the work had started. Both are real, so the condition is
	 * written to hold either way rather than split into two statements: with a
	 * number, this is the taking that owns the row; without one, the owner name
	 * and the running state are all there is to check, and all there was to begin
	 * with.
	 */
	public boolean release(long executionId, String workerId, OptionalInt claimCount, int backoffSeconds) {
		MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("id", executionId)
				.addValue(WORKER_ID, workerId)
				.addValue("claimCount", claimCount.isPresent() ? claimCount.getAsInt() : null, Types.INTEGER)
				.addValue("availableAt", LocalDateTime.now(clock).plusSeconds(backoffSeconds));

		return jdbcTemplate.update(RELEASE, parameters) == 1;
	}

	/**
	 * Renews every lease this worker holds in one statement. One round trip for
	 * all of them, from a thread that does no work of its own - a renewal that
	 * depended on the working thread would lapse whenever that thread sat in
	 * {@code waitFor} on an external tool.
	 *
	 * @return the takings the database renewed, which is what tells a worker which
	 * of the ones it believes it holds it really still does
	 */
	public List<ExecutionPossession> renewLeases(String workerId, List<ExecutionPossession> takings,
			int leaseSeconds) {
		if (takings.isEmpty()) {
			return List.of();
		}

		LocalDateTime now = LocalDateTime.now(clock);

		MapSqlParameterSource parameters = new MapSqlParameterSource().addValue(WORKER_ID, workerId)
				.addValue("now", now).addValue("leaseUntil", now.plusSeconds(leaseSeconds))
				.addValue("ids", takings.stream().map(ExecutionPossession::executionId).toArray(Long[]::new))
				.addValue("claimCounts",
						takings.stream().map(ExecutionPossession::claimCount).toArray(Integer[]::new));

		return jdbcTemplate.query(RENEW, parameters, (rs, _) -> new ExecutionPossession(rs.getLong("id"), workerId,
				rs.getInt("claim_count")));
	}

	/**
	 * Whether this taking is still in force, holding it so for the rest of the
	 * caller's transaction.
	 *
	 * @return false when the execution has moved on, has been recovered, or its
	 * lease has run out - in every one of those the caller must write nothing
	 */
	public boolean pin(long executionId, String workerId, int claimCount) {
		MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("id", executionId)
				.addValue(WORKER_ID, workerId).addValue("claimCount", claimCount)
				.addValue("now", LocalDateTime.now(clock));

		return !jdbcTemplate.queryForList(PIN, parameters, Integer.class).isEmpty();
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
	 * @return true when the row was still the abandoned one and is now waiting
	 * again
	 */
	public boolean requeue(long executionId) {
		LocalDateTime now = LocalDateTime.now(clock);

		return jdbcTemplate.update(REQUEUE,
				Map.of("id", executionId, "now", now, "availableAt", now)) == 1;
	}

	/**
	 * Closes an abandoned execution that must not be run again.
	 *
	 * @return true when this caller is the one that ended it, and so the one that
	 * should write the sentence and the history for it
	 */
	public boolean endAbandoned(long executionId, ExecutionStatus status, String messageCode, String messageArgs) {
		MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("id", executionId)
				.addValue("status", status.name()).addValue("now", LocalDateTime.now(clock))
				.addValue("messageCode", messageCode).addValue("messageArgs", messageArgs);

		return jdbcTemplate.update(END_ABANDONED, parameters) == 1;
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