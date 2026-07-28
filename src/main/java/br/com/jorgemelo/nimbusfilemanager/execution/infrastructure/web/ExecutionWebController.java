package br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.web;

import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionDetailLabels;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.ExecutionStatusNames;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;

@Controller
public class ExecutionWebController {

	private static final String ATTR_EXECUTION = "execution";
	private static final String ATTR_STEPS = "steps";
	private static final String ATTR_ERRORS = "errors";
	private static final String ATTR_MOVEMENTS = "movements";
	private static final String ATTR_MOVEMENT_SUMMARY = "movementSummary";
	private static final String VIEW_EXECUTION_DETAIL = "app/execution-detail";
	private static final String ORGANIZATION = ExecutionType.ORGANIZATION.name();
	private static final Set<String> REPROCESSABLE_STATUSES = Set.of(ExecutionStatus.INTERRUPTED.name(),
			ExecutionStatus.CANCELLED.name(), ExecutionStatus.ERROR.name(),
			ExecutionStatus.FINISHED_WITH_ERRORS.name());

	private final ExecutionQueryService executionQueryService;
	private final OrganizationService organizationService;
	private final ExecutionDetailLabels detailLabels;

	public ExecutionWebController(ExecutionQueryService executionQueryService, OrganizationService organizationService,
			ExecutionDetailLabels detailLabels) {
		this.executionQueryService = executionQueryService;
		this.organizationService = organizationService;
		this.detailLabels = detailLabels;
	}

	@GetMapping("/app/executions/{id}")
	public String execution(@PathVariable UUID id, Model model) {
		ExecutionResponse execution = executionQueryService.get(id);

		// While the run is still active, detail would render with incomplete data, so
		// send the user to the live progress screen (also how they resume watching a
		// run).
		if (ExecutionStatusNames.IN_PROGRESS_NAMES.contains(execution.status())) {
			return "redirect:/app/progress/" + id + "?kind=" + progressKind(execution);
		}

		model.addAttribute(ATTR_EXECUTION, execution);
		model.addAttribute(ATTR_STEPS, executionQueryService.steps(id));
		model.addAttribute(ATTR_ERRORS, executionQueryService.errors(id));
		model.addAttribute(ATTR_MOVEMENTS, executionQueryService.movements(id));
		model.addAttribute(ATTR_MOVEMENT_SUMMARY, executionQueryService.movementSummary(id));

		addPermissions(model, execution);
		addLabels(model);

		return VIEW_EXECUTION_DETAIL;
	}

	private String progressKind(ExecutionResponse execution) {
		if (!ORGANIZATION.equals(execution.executionType())) {
			return "inventory";
		}

		return Boolean.TRUE.equals(execution.executeFlag()) ? "organization-execute" : "organization-preview";
	}

	@PostMapping("/app/executions/{id}/undo")
	public String undo(@PathVariable UUID id, Model model) {
		ExecutionResponse execution = executionQueryService.get(id);

		model.addAttribute(ATTR_EXECUTION, execution);
		model.addAttribute("undo", organizationService.undoPublic(id));
		model.addAttribute(ATTR_STEPS, executionQueryService.steps(id));
		model.addAttribute(ATTR_ERRORS, executionQueryService.errors(id));
		model.addAttribute(ATTR_MOVEMENTS, executionQueryService.movements(id));
		model.addAttribute(ATTR_MOVEMENT_SUMMARY, executionQueryService.movementSummary(id));

		addPermissions(model, execution);
		addLabels(model);

		return VIEW_EXECUTION_DETAIL;
	}

	/**
	 * Localized labels for the movement/error enums the audit tables render, so the
	 * template looks up ready text by code instead of translating any enum itself.
	 */
	private void addLabels(Model model) {
		model.addAttribute("movementStatusLabels", detailLabels.movementStatuses());
		model.addAttribute("movementReasonLabels", detailLabels.movementReasons());
		model.addAttribute("executionErrorTypeLabels", detailLabels.executionErrorTypes());
	}

	/**
	 * Whether the user may undo/reprocess this run, decided here so the template
	 * never combines type and status itself. Only organization runs can be undone;
	 * reprocessing reopens the form for a partially-done (interrupted/failed) run.
	 */
	private void addPermissions(Model model, ExecutionResponse execution) {
		boolean organization = ORGANIZATION.equals(execution.executionType());

		model.addAttribute("canUndo", organization);
		model.addAttribute("canReprocess", organization && REPROCESSABLE_STATUSES.contains(execution.status()));
	}
}