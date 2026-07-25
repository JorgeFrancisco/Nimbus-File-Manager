package br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.web;

import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;

/**
 * Generic polling screen used by Inventory, Organization Preview and
 * Organization Execute once those operations were moved to background threads.
 * The page itself polls GET /api/executions/{id} via JS and shows a percentage
 * progress bar; "kind" tells the template where to send the user once the
 * execution reaches a terminal status.
 */
@Controller
public class ProgressWebController {

	private final ExecutionCancellationService executionCancellationService;
	private final ExecutionQueryService executionQueryService;

	@Autowired
	public ProgressWebController(ExecutionCancellationService executionCancellationService,
			ExecutionQueryService executionQueryService) {
		this.executionCancellationService = executionCancellationService;
		this.executionQueryService = executionQueryService;
	}

	@GetMapping("/app/progress/{executionId}")
	public String progress(@PathVariable UUID executionId, @RequestParam(defaultValue = "inventory") String kind,
			Model model) {
		model.addAttribute("executionId", executionId);
		model.addAttribute("kind", kind);

		return "app/execution-progress";
	}

	/**
	 * Asks the background thread running this execution to stop at the next safe
	 * checkpoint (next file, next candidate, next move). "requested"=false means
	 * there was nothing to cancel - the execution already finished (or this id
	 * never existed), which the JS treats the same as a no-op instead of an error.
	 */
	@PostMapping("/app/progress/{executionId}/cancel")
	@ResponseBody
	public Map<String, Boolean> cancel(@PathVariable UUID executionId) {
		return Map.of("requested",
				executionCancellationService.requestCancellation(executionQueryService.internalId(executionId)));
	}
}