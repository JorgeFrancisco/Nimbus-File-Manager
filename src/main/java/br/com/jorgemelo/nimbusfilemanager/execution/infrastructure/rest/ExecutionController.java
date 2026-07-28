package br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.rest;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionErrorResponse;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionStepResponse;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.MovementResponse;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.repository.projection.ExecutionErrorSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/api/executions")
public class ExecutionController {

	private final ExecutionQueryService executionQueryService;

	public ExecutionController(ExecutionQueryService executionQueryService) {
		this.executionQueryService = executionQueryService;
	}

	@GetMapping
	@Operation(summary = "Returns recent executions")
	public List<ExecutionResponse> list() {
		return executionQueryService.list();
	}

	@GetMapping("/{id}")
	@Operation(summary = "Returns execution details")
	public ExecutionResponse get(@PathVariable UUID id) {
		return executionQueryService.get(id);
	}

	@GetMapping("/{id}/steps")
	@Operation(summary = "Returns execution steps")
	public List<ExecutionStepResponse> steps(@PathVariable UUID id) {
		return executionQueryService.steps(id);
	}

	@GetMapping("/{id}/errors")
	@Operation(summary = "Returns execution errors")
	public List<ExecutionErrorResponse> errors(@PathVariable UUID id) {
		return executionQueryService.errors(id);
	}

	@GetMapping("/{id}/errors/summary")
	@Operation(summary = "Returns execution errors summary")
	public List<ExecutionErrorSummaryResponse> errorSummary(@PathVariable UUID id) {
		return executionQueryService.errorSummary(id);
	}

	@GetMapping("/{id}/movements")
	@Operation(summary = "Returns execution movement records")
	public List<MovementResponse> movements(@PathVariable UUID id) {
		return executionQueryService.movements(id);
	}
}