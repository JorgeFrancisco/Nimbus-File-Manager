package br.com.jorgemelo.nimbusfilemanager.worker.infrastructure.rest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerAvailability;
import br.com.jorgemelo.nimbusfilemanager.worker.application.dto.WorkerAvailabilityResponse;
import io.swagger.v3.oas.annotations.Operation;

/**
 * Whether there is an executor, for whoever needs to tell "being processed"
 * from "queued and nobody is coming".
 *
 * <p>
 * Facts only. It says nothing about what a screen should do when the answer is
 * no, because that differs by what was queued: a reconcile nobody asked for is
 * not the rename someone is watching. The decision belongs to the capability,
 * and putting it here would hand the same decision to all of them.
 */
@RestController
@RequestMapping("/api/worker")
public class WorkerHealthController {

	private final WorkerAvailability workerAvailability;

	public WorkerHealthController(WorkerAvailability workerAvailability) {
		this.workerAvailability = workerAvailability;
	}

	@GetMapping
	@Operation(summary = "Reports whether a worker is alive to run queued work",
			description = "Derived from the heartbeat each worker process writes. Answers how many instances were"
					+ " heard from recently and when the most recent one was seen, so a caller can tell a slow"
					+ " queue from an absent executor.")
	public WorkerAvailabilityResponse current() {
		return workerAvailability.current();
	}
}