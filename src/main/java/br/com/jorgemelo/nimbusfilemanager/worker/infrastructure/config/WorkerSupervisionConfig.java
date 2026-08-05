package br.com.jorgemelo.nimbusfilemanager.worker.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerSupervisor;
import br.com.jorgemelo.nimbusfilemanager.worker.application.constants.WorkerConstants;

/**
 * Starts the worker process, when there is one to start.
 *
 * <p>
 * The profile expression is where the three roles differ, and the only place
 * they do. An application on its own starts a second JVM; a worker starts
 * nothing, because it <em>is</em> the worker; and the combined role already has
 * the worker inside this process, so starting another would give two workers
 * claiming from one queue. Writing that as {@code app & !worker} keeps it a
 * question about composition instead of a flag every class has to remember to
 * ask about.
 *
 * <p>
 * On {@code ApplicationReadyEvent}, so the worker connects to a database this
 * process has already started and migrated - the ordering the schema check on
 * the worker side depends on.
 *
 * <p>
 * The property exists for one reason: a test that starts a context is not a
 * running product, and letting each one launch a JVM would mean hundreds of
 * them. It defaults to on, so nothing has to be configured for the real thing
 * to work.
 */
@Configuration
@Profile(NimbusProfiles.APP + " & !" + NimbusProfiles.WORKER)
@ConditionalOnProperty(name = WorkerConstants.SUPERVISE_PROPERTY, havingValue = "true", matchIfMissing = true)
public class WorkerSupervisionConfig {

	private final WorkerSupervisor workerSupervisor;

	public WorkerSupervisionConfig(WorkerSupervisor workerSupervisor) {
		this.workerSupervisor = workerSupervisor;
	}

	@EventListener(ApplicationReadyEvent.class)
	void startWorker() {
		workerSupervisor.start();
	}
}