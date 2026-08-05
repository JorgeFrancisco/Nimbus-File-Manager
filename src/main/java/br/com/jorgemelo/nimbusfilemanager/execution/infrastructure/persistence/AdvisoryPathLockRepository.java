package br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.stereotype.Repository;

import com.zaxxer.hikari.HikariDataSource;

import br.com.jorgemelo.nimbusfilemanager.execution.application.PathLockKey;

/**
 * Path exclusion that outlives a transaction, held as PostgreSQL session
 * advisory locks.
 *
 * <p>
 * A row lock cannot do this job: it only lasts while its transaction is open,
 * and keeping a transaction open for the hours a conversion takes is exactly
 * what must not happen. A session advisory lock survives the commit, costs no
 * row and no write, and - the property that makes it the right tool - is
 * released by the server the moment the connection dies, so a killed process
 * leaves nothing behind to expire or clean up.
 *
 * <p>
 * Every operation borrows a connection of its own. Advisory locks belong to the
 * session, so two operations sharing one connection could release each other's
 * locks; and the connection is always returned with
 * {@code pg_advisory_unlock_all}, because a pooled connection handed back while
 * still holding a lock would carry it into whatever borrows it next.
 */
@Repository
public class AdvisoryPathLockRepository {

	private static final String TRY_LOCK_EXCLUSIVE = "SELECT pg_try_advisory_lock(?)";

	/**
	 * The mode ancestors are taken in. Shared locks do not conflict with one
	 * another, so two operations in sibling folders both hold their common parents
	 * and neither waits - while an operation wanting a parent exclusively still
	 * collides with both.
	 */
	private static final String TRY_LOCK_SHARED = "SELECT pg_try_advisory_lock_shared(?)";

	private static final String UNLOCK_ALL = "SELECT pg_advisory_unlock_all()";

	/** How PostgreSQL names the two advisory modes in {@code pg_locks}. */
	private static final String EXCLUSIVE_MODE = "ExclusiveLock";

	private static final String SHARED_MODE = "ShareLock";

	/**
	 * Asks the server which of these keys this very session still holds, and in
	 * which mode. Not {@code SELECT 1}: that proves the connection answers, which
	 * a reconnected one does happily - while holding nothing. And not a bare count
	 * either: an exclusive claim that came back as shared is not the same claim,
	 * so the mode is part of what has to still be true.
	 */
	private static final String HELD_BY_THIS_SESSION = """
			SELECT count(*) FROM pg_locks
			 WHERE locktype = 'advisory' AND pid = pg_backend_pid() AND granted
			   AND mode = ?
			   AND ((classid::bigint << 32) | objid::bigint) = ANY(?)
			""";

	private static final String HELD_BY_ANYONE = """
			SELECT count(*) FROM pg_locks
			 WHERE locktype = 'advisory'
			   AND ((classid::bigint << 32) | objid::bigint) = ANY(?)
			""";

	private final DataSource dataSource;

	public AdvisoryPathLockRepository(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/**
	 * Opens a session for one operation to hold its locks in, outside the shared
	 * pool.
	 *
	 * <p>
	 * Not from the {@code DataSource}, and the reason is not theoretical: a pooled
	 * connection borrowed here is one the application cannot use for the hours a
	 * conversion runs, and inside a transaction the pool may hand back the very
	 * connection that transaction is using - closing it when the lock is released
	 * then kills the transaction underneath. A session of its own has neither
	 * problem, and it is also what makes the lock die with the process rather than
	 * linger on a connection returned to the pool.
	 */
	public Connection openLockSession() throws SQLException {
		HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);

		// The pool's own coordinates rather than the configured ones: the URL in the
		// properties is not always the database in use - a test binds a container, and
		// the embedded cluster picks its port at runtime.
		return DriverManager.getConnection(pool.getJdbcUrl(), pool.getUsername(), pool.getPassword());
	}

	/**
	 * Takes every key, in the order given - which is ascending by key, decided in
	 * {@code OperationPathKey}. A total order over the keys is what makes circular
	 * waiting impossible between two operations wanting the same paths, so this
	 * method must never reorder what it receives.
	 *
	 * <p>
	 * Returns false as soon as one key is unavailable, having released the ones
	 * already taken: holding half a chain would exclude nothing while still
	 * blocking others.
	 */
	public boolean tryLockAll(Connection connection, Collection<PathLockKey> keys) throws SQLException {
		for (PathLockKey key : keys) {
			if (!tryLock(connection, key)) {
				unlockAll(connection);

				return false;
			}
		}

		return true;
	}

	public void unlockAll(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(UNLOCK_ALL)) {
			statement.execute();
		}
	}

	/**
	 * Whether this session still owns all of {@code keys}. Anything less means the
	 * server restarted or the connection was replaced underneath, and the
	 * operation has lost the exclusion it believed it had.
	 */
	public boolean stillHolds(Connection connection, Collection<PathLockKey> keys) throws SQLException {
		List<Long> exclusive = numbersOf(keys, true);
		List<Long> shared = numbersOf(keys, false);

		return heldInMode(connection, EXCLUSIVE_MODE, exclusive) == exclusive.size()
				&& heldInMode(connection, SHARED_MODE, shared) == shared.size();
	}

	private long heldInMode(Connection connection, String mode, List<Long> keys) throws SQLException {
		if (keys.isEmpty()) {
			return 0;
		}

		try (PreparedStatement statement = connection.prepareStatement(HELD_BY_THIS_SESSION)) {
			statement.setString(1, mode);
			statement.setArray(2, connection.createArrayOf("bigint", keys.toArray(Long[]::new)));

			try (ResultSet resultSet = statement.executeQuery()) {
				return resultSet.next() ? resultSet.getLong(1) : 0;
			}
		}
	}

	/**
	 * Whether anyone at all holds any of these keys, for the callers that only ask
	 * whether a tree is busy and take no lock themselves.
	 */
	public boolean lockedByAnyone(Collection<PathLockKey> keys) throws SQLException {
		try (Connection connection = openLockSession();
				PreparedStatement statement = connection.prepareStatement(HELD_BY_ANYONE)) {
			statement.setArray(1, connection.createArrayOf("bigint", numbersOf(keys).toArray(Long[]::new)));

			try (ResultSet resultSet = statement.executeQuery()) {
				return resultSet.next() && resultSet.getLong(1) > 0;
			}
		}
	}

	private List<Long> numbersOf(Collection<PathLockKey> keys) {
		return keys.stream().map(PathLockKey::key).toList();
	}

	private List<Long> numbersOf(Collection<PathLockKey> keys, boolean exclusive) {
		return keys.stream().filter(key -> key.exclusive() == exclusive).map(PathLockKey::key).toList();
	}

	private boolean tryLock(Connection connection, PathLockKey key) throws SQLException {
		// Two literal statements rather than one chosen into a variable: the SQL here
		// is fixed either way, and keeping it literal keeps the bytecode analyser's
		// injection detector honest instead of trained to ignore this file.
		if (key.exclusive()) {
			try (PreparedStatement statement = connection.prepareStatement(TRY_LOCK_EXCLUSIVE)) {
				return granted(statement, key.key());
			}
		}

		try (PreparedStatement statement = connection.prepareStatement(TRY_LOCK_SHARED)) {
			return granted(statement, key.key());
		}
	}

	private boolean granted(PreparedStatement statement, long key) throws SQLException {
		statement.setLong(1, key);

		try (ResultSet resultSet = statement.executeQuery()) {
			return resultSet.next() && resultSet.getBoolean(1);
		}
	}

}