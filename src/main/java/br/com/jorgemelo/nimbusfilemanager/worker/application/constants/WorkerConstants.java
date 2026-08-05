package br.com.jorgemelo.nimbusfilemanager.worker.application.constants;

/**
 * Contract data constants of the worker domain.
 */
public final class WorkerConstants {

	/**
	 * The application's own process id, handed to the worker on the command line.
	 *
	 * <p>
	 * Named here rather than spelled out on both sides: the application writes the
	 * argument and the worker binds it, and a prefix typed twice is a prefix that
	 * can differ once.
	 */
	public static final String PARENT_PID_PROPERTY = "nimbus-file-manager.worker.parent-pid";

	/**
	 * Whether this JVM takes part in the real two-process lifecycle. On by default;
	 * off in a process that merely hosts the roles, such as the test suite, where
	 * neither starting a worker nor ending this process would mean anything.
	 */
	public static final String SUPERVISE_PROPERTY = "nimbus-file-manager.worker.supervise";

	private WorkerConstants() {
	}
}