package br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import javax.sql.DataSource;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.constants.ExecutionChannels;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Hears "something was queued" and lets the claim loops wake on it.
 *
 * <p>
 * The signal is an accelerator and nothing else. Everything here may fail - the
 * connection may never open, the notification may be lost on a reconnect, a
 * worker may not have been listening when it was sent - and in every one of
 * those cases the loops simply wait out their budget and ask the queue anyway,
 * which is precisely what they did before this class existed. Nothing that is
 * needed to run work travels through here.
 *
 * <p>
 * One connection and one thread for the whole process, rather than one per
 * loop. {@code LISTEN} binds the subscription to a session, so a connection
 * borrowed from the pool and returned would unsubscribe; this one is held for
 * as long as the worker runs. The loops do not touch it: they wait on a counter
 * this thread bumps, so a single notification wakes all of them at once and a
 * spurious wake costs one query against the queue.
 *
 * <p>
 * A reconnect deliberately signals once. Whatever was published while the
 * socket was down was never delivered to anybody, and the cheapest correct
 * reaction to "I was deaf for a moment" is to go and look.
 */
@Slf4j
@Component
@Profile(NimbusProfiles.WORKER)
public class ExecutionQueueSignals {

	/**
	 * How long the listening thread blocks on the socket before looping. Nothing
	 * is queried and nothing is sent - the block is a socket read - so this is not
	 * a polling interval, it is how long the process can take to notice it was
	 * asked to stop. Kept short for that reason alone.
	 */
	private static final int BLOCK_MILLIS = 1_000;

	private static final long RECONNECT_BACKOFF_MILLIS = 5_000;

	private static final long SHUTDOWN_JOIN_MILLIS = 3_000;

	/**
	 * Composed once, at compile time: the channel is not a value that can arrive
	 * from anywhere, and {@code LISTEN} takes an identifier rather than a
	 * parameter, so there is nothing here to bind.
	 */
	private static final String SUBSCRIBE = "LISTEN " + ExecutionChannels.QUEUED;

	private final DataSource dataSource;

	private final ReentrantLock lock = new ReentrantLock();
	private final Condition signalled = lock.newCondition();

	private long signals;
	private volatile boolean listening;

	private final AtomicReference<Thread> listener = new AtomicReference<>();

	public ExecutionQueueSignals(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	public void start() {
		listening = true;

		Thread thread = new Thread(this::listenUntilStopped, "nimbus-execution-signals");

		thread.setDaemon(true);
		thread.start();

		listener.set(thread);
	}

	/**
	 * The number of signals seen so far. Read before asking the queue for work,
	 * so that a notification arriving while that question is in flight is not
	 * mistaken for one that arrived before it - which would have the loop wait
	 * out its whole budget over work that had just been queued.
	 */
	public long signalCount() {
		lock.lock();

		try {
			return signals;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Waits for a signal newer than the one given, or until the budget runs out.
	 *
	 * @return true if a newer signal arrived, false if the budget expired - and
	 * false is not an error, it is the ordinary idle case
	 */
	public boolean awaitSignalAfter(long seen, Duration budget) {
		lock.lock();

		try {
			long remaining = budget.toNanos();

			while (signals == seen && remaining > 0) {
				remaining = signalled.awaitNanos(remaining);
			}

			return signals != seen;
		} catch (InterruptedException _) {
			Thread.currentThread().interrupt();

			return false;
		} finally {
			lock.unlock();
		}
	}

	private void listenUntilStopped() {
		while (listening) {
			try (Connection connection = dataSource.getConnection()) {
				subscribe(connection);

				// Anything published while this process had no subscription was
				// delivered to nobody. Looking once is cheaper than reasoning about
				// what was missed.
				signal();

				drain(connection.unwrap(PGConnection.class));
			} catch (SQLException exception) {
				log.debug("Lost the queue notification channel; falling back to polling until it is back", exception);

				backOff();
			}
		}
	}

	private void subscribe(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute(SUBSCRIBE);
		}
	}

	private void drain(PGConnection connection) throws SQLException {
		while (listening) {
			PGNotification[] notifications = connection.getNotifications(BLOCK_MILLIS);

			if (notifications != null && notifications.length > 0) {
				signal();
			}
		}
	}

	private void signal() {
		lock.lock();

		try {
			signals++;

			signalled.signalAll();
		} finally {
			lock.unlock();
		}
	}

	private void backOff() {
		try {
			Thread.sleep(RECONNECT_BACKOFF_MILLIS);
		} catch (InterruptedException _) {
			Thread.currentThread().interrupt();

			listening = false;
		}
	}

	/**
	 * The connection is left for the listening thread to return, which it does on
	 * its next block boundary - a second at most. Closing it from here would be
	 * closing a pooled connection from a thread that did not borrow it, and the
	 * pool would then wait on a checkout that is never coming back.
	 */
	@PreDestroy
	void stop() {
		listening = false;

		Thread thread = listener.getAndSet(null);

		if (thread == null) {
			return;
		}

		thread.interrupt();

		try {
			// Long enough for a read that has just been unblocked to unwind, short
			// enough that a thread which somehow will not end is not what decides how
			// long this process takes to exit.
			thread.join(SHUTDOWN_JOIN_MILLIS);
		} catch (InterruptedException _) {
			Thread.currentThread().interrupt();
		}
	}
}