package br.com.jorgemelo.nimbusfilemanager.processing.application;

import java.io.IOException;

/**
 * Work that runs while holding a permit from {@link ExternalToolGate}.
 *
 * <p>
 * The two checked exceptions are the two an external tool actually produces:
 * the process failing to start or to be read, and the wait for a permit or for
 * the process being interrupted. Declaring {@code Exception} instead would make
 * every caller of the gate catch a type that says nothing, and would let an
 * unrelated checked exception travel through a seam that exists to run one
 * command.
 */
@FunctionalInterface
public interface GatedAction<T> {

	T run() throws IOException, InterruptedException;
}