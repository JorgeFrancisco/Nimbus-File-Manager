package br.com.jorgemelo.nimbusfilemanager.worker.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerProcessExit;
import br.com.jorgemelo.nimbusfilemanager.worker.application.constants.WorkerConstants;
import lombok.extern.slf4j.Slf4j;

/**
 * Standing down without ending anything, for a JVM that is hosting the worker
 * role rather than being a worker.
 *
 * <p>
 * The test suite is the case that made this necessary: it starts worker
 * contexts by the dozen, in a process nobody supervises and nobody is watching
 * the exit of. A context that decided to stand down there would end the run that
 * was exercising it - and did, once, taking a whole suite with it thirty seconds
 * after the call.
 *
 * <p>
 * The decision that leads here is still made, still logged, and still stops the
 * claiming. Only the last step is left out, because in this process there is
 * nothing whose ending would mean what it means in a worker.
 */
@Slf4j
@Component
@Profile(NimbusProfiles.WORKER)
@ConditionalOnProperty(name = WorkerConstants.SUPERVISE_PROPERTY, havingValue = "false")
public class NoOpWorkerProcessExit implements WorkerProcessExit {

	@Override
	public void end(int exitCode) {
		log.info("A worker in this process stood down with code {}; the process itself is left alone", exitCode);
	}
}