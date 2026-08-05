package br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionChannels;

/**
 * Tells whoever is listening that the queue has something new.
 *
 * <p>
 * Called inside the transaction that writes the row, and that placement is the
 * whole guarantee. PostgreSQL holds a notification until the transaction
 * commits and discards it if the transaction rolls back, so there is no
 * ordering in which a worker is woken, queries the queue, and finds nothing
 * because the row is not committed yet - the notification cannot exist before
 * the row does. Emitting it after the commit instead would open exactly that
 * window, and would also lose the signal if the process died in between.
 *
 * <p>
 * {@code pg_notify} rather than the {@code NOTIFY} statement: the channel
 * arrives as a bound parameter instead of being concatenated into SQL, which is
 * the same reason every other query here binds its values. The second argument
 * is the empty payload - deliberately nothing, see {@link ExecutionChannels}.
 */
@Repository
public class ExecutionQueueNotifier {

	private static final String SIGNAL = "SELECT pg_notify(:channel, '')";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public ExecutionQueueNotifier(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void workWasQueued() {
		jdbcTemplate.query(SIGNAL, new MapSqlParameterSource("channel", ExecutionChannels.QUEUED), _ -> null);
	}
}