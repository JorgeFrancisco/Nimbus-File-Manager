package br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;

class ProgressWebControllerTest {

	private final ExecutionCancellationService executionCancellationService = new ExecutionCancellationService();
	private final ExecutionQueryService executionQueryService = mock(ExecutionQueryService.class);
	private final ProgressWebController controller = new ProgressWebController(executionCancellationService,
			executionQueryService);

	@Test
	void progressShouldExposeExecutionIdAndKindToTemplate() {
		UUID executionId = UUID.randomUUID();
		ExtendedModelMap model = new ExtendedModelMap();

		String view = controller.progress(executionId, "organization-execute", model);

		Assertions.assertThat(view).isEqualTo("app/execution-progress");
		Assertions.assertThat(model).containsEntry("executionId", executionId).containsEntry("kind",
				"organization-execute");
	}

	@Test
	void cancelShouldRequestCancellationWhenTheExecutionIsStillRunning() {
		UUID publicId = UUID.randomUUID();

		when(executionQueryService.internalId(publicId)).thenReturn(5L);
		executionCancellationService.register(5L);

		Map<String, Boolean> requested = controller.cancel(publicId);

		Assertions.assertThat(requested).isEqualTo(Map.of("requested", true));
		Assertions.assertThat(executionCancellationService.isCancelled(5L)).isTrue();
	}

	/**
	 * An execution that already finished has no in-memory flag left, so the JS gets
	 * "requested": false and treats it as a no-op instead of an error.
	 */
	@Test
	void cancelShouldReportNothingRequestedWhenTheExecutionIsNoLongerRunning() {
		UUID publicId = UUID.randomUUID();

		when(executionQueryService.internalId(publicId)).thenReturn(99L);

		Assertions.assertThat(controller.cancel(publicId)).isEqualTo(Map.of("requested", false));
	}
}