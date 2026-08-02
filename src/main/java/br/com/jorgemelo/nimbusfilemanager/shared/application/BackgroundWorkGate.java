package br.com.jorgemelo.nimbusfilemanager.shared.application;

import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Says when background work should stand down, and when its failures are
 * expected rather than worth reporting.
 *
 * <p>
 * Two states qualify, and they look identical from the inside: the application
 * is closing, or the catalog is being replaced by a restore. In both, the
 * database a periodic task is about to query is either going away or currently
 * missing the table it wants - and a stack trace about it describes the
 * situation, not a defect.
 *
 * <p>
 * Both had been discovered the hard way, separately. The watcher already kept a
 * shutdown flag of its own and used it to choose between {@code debug} and
 * {@code error}; what it could not know was that a restore leaves the same
 * marks, so the first real one printed {@code relation "execution" does not
 * exist} as a failure. Keeping the answer in one place is what lets every loop
 * ask the same question.
 */
@Component
public class BackgroundWorkGate {

	private volatile boolean shuttingDown;
	private volatile boolean restoring;

	/**
	 * Published at the very start of the close - before any bean is destroyed -
	 * which is exactly when work in flight should stop reaching for the database
	 * and stop complaining that it is gone.
	 */
	@EventListener
	public void onContextClosed(ContextClosedEvent event) {
		shuttingDown = true;
	}

	public void restoreStarted() {
		restoring = true;
	}

	public void restoreFinished() {
		restoring = false;
	}

	/** Whether a restore is replacing the catalog right now. */
	public boolean restoring() {
		return restoring;
	}

	/**
	 * Whether periodic work should skip this round, and whether a failure it just
	 * caught is explained by the state the application is in.
	 */
	public boolean standDown() {
		return shuttingDown || restoring;
	}
}