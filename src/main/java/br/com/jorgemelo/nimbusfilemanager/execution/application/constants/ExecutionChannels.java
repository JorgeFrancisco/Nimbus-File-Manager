package br.com.jorgemelo.nimbusfilemanager.execution.application.constants;

/**
 * The database channel a worker listens on for "something was queued".
 *
 * <p>
 * A name shared by two processes and two builds, which is why it is a constant
 * and not a literal in each side. What travels on it is nothing at all: the
 * notification carries no payload, because the command is the row and a channel
 * that carried anything else would be a second, weaker copy of it - one that is
 * lost on a reconnect, never delivered to a worker that was not listening, and
 * impossible to read again after a restart.
 */
public final class ExecutionChannels {

	public static final String QUEUED = "nimbus_execution_queued";

	private ExecutionChannels() {
	}
}