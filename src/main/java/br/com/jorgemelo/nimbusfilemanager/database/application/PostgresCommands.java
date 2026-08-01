package br.com.jorgemelo.nimbusfilemanager.database.application;

import br.com.jorgemelo.nimbusfilemanager.database.domain.enums.ClusterStartOutcome;
import br.com.jorgemelo.nimbusfilemanager.database.domain.enums.ClusterStopMode;

/**
 * The PostgreSQL commands the application needs, as a port.
 *
 * <p>
 * The lifecycle around them - when to create a cluster, which port to try next,
 * how long to wait before giving up on a clean stop - is decision-making, and
 * it belongs where a test can exercise every branch of it without a server on
 * the machine. Spawning the executables is what stays on the other side of this
 * interface.
 */
public interface PostgresCommands {

	/**
	 * Creates a cluster and the application's role, with the given password.
	 *
	 * @return whether the cluster was created
	 */
	boolean initialize(String password);

	/**
	 * Starts the server on the given port.
	 *
	 * <p>
	 * A port that is taken has to come back as its own outcome rather than as a
	 * failure: between choosing a free port and the server binding it, anything
	 * else on the machine may have taken it, and the answer to that is another
	 * port rather than an error on screen.
	 */
	ClusterStartOutcome start(int port);

	/**
	 * Stops the server, waiting for it to exit.
	 *
	 * @return whether the server had stopped by the time this returned
	 */
	boolean stop(ClusterStopMode mode);

	/**
	 * Creates the application's database on a running server.
	 *
	 * @return whether the database exists once this returns
	 */
	boolean createDatabase(int port, String password);
}