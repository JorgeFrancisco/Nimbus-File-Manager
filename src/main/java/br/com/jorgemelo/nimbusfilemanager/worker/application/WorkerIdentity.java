package br.com.jorgemelo.nimbusfilemanager.worker.application;

import java.lang.management.ManagementFactory;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;

/**
 * Who this worker is, as written into {@code claimed_by}.
 *
 * <p>
 * Process id plus start time rather than a random id: a worker that is killed
 * and restarted must not look like the one that died, or a stale lease could be
 * renewed by its own replacement. The pair is unique for as long as it matters,
 * and reading a claim tells you which process to look for.
 */
@Component
@Profile(NimbusProfiles.WORKER)
public class WorkerIdentity {

	private final String workerId;

	public WorkerIdentity() {
		this.workerId = "worker-" + ProcessHandle.current().pid() + "-"
				+ ManagementFactory.getRuntimeMXBean().getStartTime();
	}

	public String workerId() {
		return workerId;
	}
}