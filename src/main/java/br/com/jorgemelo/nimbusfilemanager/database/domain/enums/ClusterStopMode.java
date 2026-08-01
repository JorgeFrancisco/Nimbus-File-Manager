package br.com.jorgemelo.nimbusfilemanager.database.domain.enums;

/**
 * How the server is asked to stop. {@code FAST} rolls back open transactions
 * and checkpoints before exiting, which is the normal path; {@code IMMEDIATE}
 * kills it and leaves the next start to recover, which is what happens after a
 * crash and is only worth doing when a clean stop has already been given time
 * to finish.
 */
public enum ClusterStopMode {

	FAST("fast"), IMMEDIATE("immediate");

	private final String argument;

	ClusterStopMode(String argument) {
		this.argument = argument;
	}

	public String argument() {
		return argument;
	}
}