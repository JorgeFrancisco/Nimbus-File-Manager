package br.com.jorgemelo.nimbusfilemanager.execution.application;

import java.io.Serial;

/**
 * Thrown when an execution asks whether it still owns its paths and the answer
 * is no.
 *
 * <p>
 * Its own type rather than a boolean everybody remembers to check: the point of
 * a checkpoint is that continuing past it is impossible, and a return value can
 * be ignored by writing nothing at all. What it interrupts is not a failure of
 * the work - the files are as they were, the moment before is a moment nobody
 * else had - so the dispatcher ends the execution as interrupted rather than
 * failed.
 */
public class OwnershipLostException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	public OwnershipLostException(String message) {
		super(message);
	}
}