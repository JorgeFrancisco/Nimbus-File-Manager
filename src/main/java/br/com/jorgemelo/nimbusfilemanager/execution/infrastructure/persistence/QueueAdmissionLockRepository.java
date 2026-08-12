package br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Serialising the decision to admit work, as PostgreSQL transaction advisory
 * locks over the identity of each request.
 *
 * <p>
 * Asking before inserting removes the duplicate that was already committed, but
 * not the one that is not committed yet: in READ COMMITTED two callers looking
 * at the same moment both see nothing, both insert, and the unique index refuses
 * one of them - which marks <em>its caller's</em> transaction rollback-only.
 * That cost a whole inventory batch its writes, silently, so the two callers are
 * made to take turns instead. The second one waits here, and by the time it
 * looks, the first has committed and there is something to find.
 *
 * <p>
 * <b>Transaction-scoped, never session-scoped.</b> The lock belongs to the
 * transaction that took it and PostgreSQL releases it on commit <em>and</em> on
 * rollback, with nothing to unlock by hand and nothing that can be carried into
 * whatever borrows the pooled connection next. That is the whole difference from
 * {@link AdvisoryPathLockRepository}, whose locks have to outlive their
 * transaction and therefore need a session and a release of their own.
 *
 * <p>
 * <b>The keys arrive sorted, and that is the point.</b> One transaction may
 * admit many requests - an inventory batch asks about every suspect it found -
 * and two transactions taking the same pair of keys in opposite orders would
 * deadlock, trading one failure for another. A total order over the numbers
 * themselves makes circular waiting impossible, so this must never reorder what
 * it receives. {@code unnest} in the FROM clause yields the array in order, and
 * {@code pg_advisory_xact_lock} is volatile, which keeps the evaluation serial
 * and in that order.
 *
 * <p>
 * None of this replaces the unique indexes. They remain the last word on what
 * two equivalent requests are; this only decides who gets to ask first.
 */
@Repository
public class QueueAdmissionLockRepository {

	private static final String TAKE = """
			SELECT pg_advisory_xact_lock(admission.key)
			  FROM unnest(CAST(:keys AS bigint[])) AS admission(key)
			""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public QueueAdmissionLockRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Takes every key, in the order given - which is ascending, decided by the
	 * caller that knows the whole set.
	 */
	public void take(Long[] ascendingKeys) {
		if (ascendingKeys.length == 0) {
			return;
		}

		jdbcTemplate.query(TAKE, new MapSqlParameterSource().addValue("keys", ascendingKeys), _ -> {
			// Taking the locks is the result; there is nothing to read back.
		});
	}
}