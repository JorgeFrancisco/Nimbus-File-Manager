package br.com.jorgemelo.nimbusfilemanager.worker.application.constants;

/**
 * What a worker's exit code says about why it stopped.
 *
 * <p>
 * The supervisor decides whether to bring another one back, and how soon, and
 * the only thing a dead process can still tell it is this number. They are
 * contract between the two roles, which is why they are named here and not
 * inline.
 */
public final class WorkerExitCodes {

	/** Asked to stop, and did. */
	public static final int ORDERLY = 0;

	/**
	 * The database schema is not the one this build was made for. Persistent by
	 * nature: nothing the worker does changes it, so restarting into the same
	 * answer is the loop the supervisor's backoff exists to stop.
	 */
	public static final int SCHEMA_INCOMPATIBLE = 3;

	/**
	 * The application that started this worker is gone. Nobody is supervising any
	 * more, so the code is for the log rather than for a decision.
	 */
	public static final int PARENT_GONE = 4;

	private WorkerExitCodes() {
	}
}