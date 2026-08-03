package br.com.jorgemelo.nimbusfilemanager.shared.application;

/**
 * Ends this run, gracefully.
 *
 * <p>
 * A port around the one call that cannot appear in a unit test: ending the
 * process would end the suite running it. Everything that decides to shut down
 * is worth asserting - that the installer was verified first, that a refusal
 * leaves the run alive - and none of that could be asserted while the decision
 * and the exit lived in the same method.
 *
 * <p>
 * Graceful matters: the embedded PostgreSQL is stopped by a shutdown handler,
 * so a process that simply exits leaves a database server behind.
 */
public interface ApplicationShutdown {

	/**
	 * Ends the run, allowing whatever asked to answer first. Returns immediately;
	 * the process ends shortly afterwards.
	 */
	void endRun();
}