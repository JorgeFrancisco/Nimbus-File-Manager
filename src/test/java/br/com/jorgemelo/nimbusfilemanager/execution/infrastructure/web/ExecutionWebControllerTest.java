package br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionDetailLabels;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.organization.application.OrganizationService;

/**
 * The public UUID-based endpoints of the execution controller: detail
 * rendering, redirect to the live progress screen while a run is still in
 * progress (with the right {@code kind}), and undo.
 */
class ExecutionWebControllerTest {

	private static final LocalDateTime NOW = LocalDateTime.parse("2026-07-12T12:00:00");
	private static final UUID ID = UUID.fromString("01890000-0000-7000-8000-000000000001");

	private final ExecutionQueryService executionQueryService = mock(ExecutionQueryService.class);
	private final OrganizationService organizationService = mock(OrganizationService.class);
	private final ExecutionWebController controller = new ExecutionWebController(executionQueryService,
			organizationService, new ExecutionDetailLabels());

	@Test
	void executionRendersDetailWhenFinished() {
		when(executionQueryService.get(ID)).thenReturn(response("INVENTORY", "FINISHED", null));
		when(executionQueryService.steps(ID)).thenReturn(List.of());
		when(executionQueryService.errors(ID)).thenReturn(List.of());
		when(executionQueryService.movements(ID)).thenReturn(List.of());

		ExtendedModelMap model = new ExtendedModelMap();

		String view = controller.execution(ID, model);

		Assertions.assertThat(view).isEqualTo("app/execution-detail");
		Assertions.assertThat(model.get("execution")).isNotNull();
		Assertions.assertThat(model).containsKeys("steps", "errors", "movements").containsEntry("canUndo", false)
				.containsEntry("canReprocess", false)
				.containsKeys("movementStatusLabels", "movementReasonLabels", "executionErrorTypeLabels");
	}

	@Test
	void executionRedirectsToInventoryProgressWhileRunning() {
		when(executionQueryService.get(ID)).thenReturn(response("INVENTORY", "RUNNING", null));

		Assertions.assertThat(controller.execution(ID, new ExtendedModelMap()))
				.isEqualTo("redirect:/app/progress/" + ID + "?kind=inventory");
	}

	@Test
	void executionRedirectsWithOrganizationExecuteKind() {
		when(executionQueryService.get(ID)).thenReturn(response("ORGANIZATION", "RUNNING", true));

		Assertions.assertThat(controller.execution(ID, new ExtendedModelMap()))
				.isEqualTo("redirect:/app/progress/" + ID + "?kind=organization-execute");
	}

	@Test
	void executionRedirectsWithOrganizationPreviewKind() {
		when(executionQueryService.get(ID)).thenReturn(response("ORGANIZATION", "RUNNING", false));

		Assertions.assertThat(controller.execution(ID, new ExtendedModelMap()))
				.isEqualTo("redirect:/app/progress/" + ID + "?kind=organization-preview");
	}

	/**
	 * The reversal is queued and followed on the progress screen, like every other
	 * run that moves files. Re-rendering this page with a summary only worked while
	 * the moving happened inside the request.
	 */
	@Test
	void undoQueuesTheReversalAndFollowsItOnTheProgressScreen() {
		UUID queuedId = UUID.randomUUID();

		when(organizationService.undoPublic(ID)).thenReturn(response(queuedId));

		Assertions.assertThat(controller.undo(ID)).isEqualTo("redirect:/app/progress/" + queuedId);
	}

	@Test
	void executionAllowsUndoAndReprocessForAFailedOrganizationRun() {
		when(executionQueryService.get(ID)).thenReturn(response("ORGANIZATION", "ERROR", true));
		when(executionQueryService.steps(ID)).thenReturn(List.of());
		when(executionQueryService.errors(ID)).thenReturn(List.of());
		when(executionQueryService.movements(ID)).thenReturn(List.of());

		ExtendedModelMap model = new ExtendedModelMap();

		controller.execution(ID, model);

		Assertions.assertThat(model).containsEntry("canUndo", true).containsEntry("canReprocess", true);
	}

	/**
	 * Undo moves files back, so the question has to say how many. The number comes
	 * from the run itself and is resolved here, because the screen is not allowed
	 * to build a sentence out of raw fields.
	 */
	@Test
	void theUndoConfirmationSaysHowManyFilesComeBack() {
		when(executionQueryService.get(ID)).thenReturn(new ExecutionResponse(1L, "ORGANIZATION", "FINISHED", NOW, NOW,
				"src", null, 4200, 4200, 0, 3814, 0, 0, null, null, "ok", true));
		when(executionQueryService.steps(ID)).thenReturn(List.of());
		when(executionQueryService.errors(ID)).thenReturn(List.of());
		when(executionQueryService.movements(ID)).thenReturn(List.of());

		ExtendedModelMap model = new ExtendedModelMap();

		controller.execution(ID, model);

		Assertions.assertThat(model.get("undoConfirmation").toString()).contains("814").doesNotContain("{0}");
	}

	private ExecutionResponse response(String type, String status, Boolean executeFlag) {
		return new ExecutionResponse(1L, type, status, NOW, NOW, "src", null, 1, 1, 0, 0, 0, 0, null, null, "ok",
				executeFlag);
	}

	private ExecutionResponse response(UUID executionId) {
		return new ExecutionResponse(executionId, "UNDO", "PENDING", null, null, "src", "target", 0, 0, 0, 0, 0, 0,
				null, null, null, true);
	}
}