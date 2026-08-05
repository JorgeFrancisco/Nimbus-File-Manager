package br.com.jorgemelo.nimbusfilemanager.execution.application;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.worker.application.ExecutionReclaim;
import lombok.extern.slf4j.Slf4j;

/**
 * Deals with the work a previous run left behind, as soon as the application is
 * up.
 *
 * <p>
 * Without it a row abandoned by a crash stays RUNNING until somebody happens to
 * start a worker: the progress screen polls a scan that is not happening, and
 * the user has no way to tell. Doing it here means they see what really became
 * of it on their next visit.
 *
 * <p>
 * What it applies is not its own idea of recovery. It used to have one - mark
 * everything interrupted - guarded by a set of ids in this JVM's memory, which
 * only ever answered for this JVM and, once the work moved to the worker, was
 * permanently empty here. The question it was really asking is answered by the
 * lease, for every process at once, so this now runs the same policy the worker
 * runs: requeue what can simply be run again, close what cannot and ask for the
 * divergence it may have left to be reconciled.
 */
@Slf4j
@Component
@Profile(NimbusProfiles.APP)
public class StartupExecutionRecoveryListener implements ApplicationListener<ApplicationReadyEvent> {

	private final ExecutionReclaim executionReclaim;

	public StartupExecutionRecoveryListener(ExecutionReclaim executionReclaim) {
		this.executionReclaim = executionReclaim;
	}

	@Override
	public void onApplicationEvent(ApplicationReadyEvent event) {
		log.info("Recovering executions whose owner stopped renewing before this start.");

		executionReclaim.reclaimAbandoned();
	}
}