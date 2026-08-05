package br.com.jorgemelo.nimbusfilemanager.worker.application;

import java.util.Optional;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.NimbusProfiles;
import br.com.jorgemelo.nimbusfilemanager.worker.application.constants.WorkerExitCodes;
import lombok.extern.slf4j.Slf4j;

/**
 * Ends this worker when the application that started it is gone.
 *
 * <p>
 * Ordered shutdown already covers the application closing properly: it asks the
 * worker to stop before it goes. What it cannot cover is the application not
 * getting to ask - a kill, a power cut, a crash - and what would be left is a
 * worker claiming work for a product nobody is running, holding the
 * installation folder against the next update, with no supervisor left to stop
 * it or restart it.
 *
 * <p>
 * Only where there is a parent to outlive: {@code worker & !app}. In the
 * combined role both halves are this one process, and a watcher on its own pid
 * would be waiting for itself.
 *
 * <p>
 * A worker somebody started by hand gets no pid and is watched by nobody, which
 * is the honest answer - there is no supervisor whose death would mean
 * anything.
 */
@Slf4j
@Component
@Profile(NimbusProfiles.WORKER + " & !" + NimbusProfiles.APP)
public class ParentProcessWatch {

	private final WorkerProperties workerProperties;
	private final WorkerStandDown workerStandDown;

	public ParentProcessWatch(WorkerProperties workerProperties, WorkerStandDown workerStandDown) {
		this.workerProperties = workerProperties;
		this.workerStandDown = workerStandDown;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void watchParent() {
		workerProperties.parentProcess().ifPresentOrElse(this::watch,
				() -> log.debug("No parent process was named, so this worker outlives nobody"));
	}

	/**
	 * Package-private so the two answers can be exercised against real processes:
	 * a pid nobody holds, and one that is alive until it is not.
	 */
	void watch(long parentPid) {
		Optional<ProcessHandle> parent = ProcessHandle.of(parentPid);

		if (parent.isEmpty()) {
			// Between the application starting this process and this process getting
			// here, the application stopped existing. Same situation as an exit, only
			// already finished.
			leave();

			return;
		}

		log.info("Watching the application process {}", parentPid);

		parent.get().onExit().thenRun(this::leave);
	}

	private void leave() {
		workerStandDown.leave("the application that started this worker is gone", WorkerExitCodes.PARENT_GONE);
	}
}