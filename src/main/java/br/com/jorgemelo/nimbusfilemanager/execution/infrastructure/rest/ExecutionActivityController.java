package br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.rest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionActivityService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionActivitySnapshot;
import io.swagger.v3.oas.annotations.Operation;

/**
 * What every authenticated page polls to know whether anything is happening.
 *
 * <p>
 * It replaces two endpoints that between them could not answer the question.
 * One reported on an execution the caller already knew about, so work started
 * afterwards stayed invisible; the other reported on fingerprint backlogs alone,
 * for a reason that stopped being true once those became executions like any
 * other. This one takes no id and knows no type: it answers what is active, and
 * an empty answer is an answer rather than a reason to stop asking.
 */
@RestController
@RequestMapping("/api/execution-activity")
public class ExecutionActivityController {

	private final ExecutionActivityService executionActivityService;

	public ExecutionActivityController(ExecutionActivityService executionActivityService) {
		this.executionActivityService = executionActivityService;
	}

	@GetMapping
	@Operation(summary = "Returns the work in flight right now",
			description = "Everything PENDING or RUNNING, in the order the queue would take it: the one worth"
					+ " drawing in full, the rest, and how many there are. Empty when nothing is running.")
	public ExecutionActivitySnapshot current() {
		return executionActivityService.current();
	}
}