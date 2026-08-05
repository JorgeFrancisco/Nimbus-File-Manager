package br.com.jorgemelo.nimbusfilemanager.worker.application;

import java.io.IOException;

/**
 * Starts the worker process.
 *
 * <p>
 * A port because the supervisor's job - retain the handle, watch for exit,
 * restart, stop in order - is worth asserting without starting a JVM per test,
 * and because building a command line is infrastructure by any measure.
 */
public interface WorkerProcessLauncher {

	Process launch() throws IOException;
}