package br.com.jorgemelo.nimbusfilemanager.worker.application;

/**
 * Ending this JVM.
 *
 * <p>
 * A port for the same reason {@link WorkerProcessLauncher} is one: the boundary
 * is a real process, and the two sides of it are exactly what a test cannot
 * have. Without it, everything that decides <em>when</em> a worker should leave
 * would be provable only by leaving - which ends the test JVM with it.
 */
public interface WorkerProcessExit {

	/**
	 * Ends this process, ordered if it can be and forced if it cannot.
	 *
	 * @param exitCode what the supervisor, if there still is one, will read
	 */
	void end(int exitCode);
}