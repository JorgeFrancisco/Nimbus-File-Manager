package br.com.jorgemelo.nimbusfilemanager.database.domain.enums;

/**
 * How an attempt to start the cluster ended. A busy port is told apart from
 * every other failure because it is the one worth retrying: the port was free
 * when it was chosen, and something else took it in between.
 */
public enum ClusterStartOutcome {

	STARTED,
	PORT_UNAVAILABLE,
	FAILED
}