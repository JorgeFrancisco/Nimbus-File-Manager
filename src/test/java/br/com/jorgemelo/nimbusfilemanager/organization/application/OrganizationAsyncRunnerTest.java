package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationFallbackMode;
import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationSubdivision;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionPhaseType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.PerformanceTelemetryService;

@ExtendWith(MockitoExtension.class)
class OrganizationAsyncRunnerTest {

	@Mock
	private OrganizationExecutor organizationExecutor;

	@Mock
	private PerformanceTelemetryService performanceTelemetryService;

	@Test
	void runPreviewShouldDelegateToExecutorAsDryRunAndRecordPlanPhase() {
		Execution execution = Execution.builder().id(1L).build();

		OrganizationExecuteRequest request = request("C:/preview-source", "C:/preview-target", true);

		runner().runPreview(request, execution);

		// Preview is the same executor loop as execute; the dry-run flag is what blocks
		// side effects, and the runner only tags the run with the PLAN telemetry phase.
		Assertions.assertThat(request.dryRunValue()).isTrue();

		verify(organizationExecutor).execute(request, execution);
		verify(performanceTelemetryService).recordMetrics(eq(1L), isNull(),
				argThat(phases -> phases.containsKey(ExecutionPhaseType.PLAN)));
	}

	@Test
	void runExecuteShouldDelegateToExecutorAndRecordMovementPhase() {
		Execution execution = Execution.builder().id(2L).build();

		OrganizationExecuteRequest request = request("C:/execute-source", "C:/execute-target", false);

		runner().runExecute(request, execution);

		verify(organizationExecutor).execute(request, execution);
		verify(performanceTelemetryService).recordMetrics(eq(2L), isNull(),
				argThat(phases -> phases.containsKey(ExecutionPhaseType.MOVEMENT)));
	}

	@Test
	void runPreviewShouldStillRecordThePhaseWhenTheExecutorFails() {
		Execution execution = Execution.builder().id(3L).build();

		OrganizationExecuteRequest request = request("C:/source", "C:/target", true);

		doThrow(new IllegalStateException("boom")).when(organizationExecutor).execute(request, execution);

		OrganizationAsyncRunner runner = runner();

		Assertions.assertThatThrownBy(() -> runner.runPreview(request, execution))
				.isInstanceOf(IllegalStateException.class);

		verify(performanceTelemetryService).recordMetrics(eq(3L), isNull(),
				argThat(phases -> phases.containsKey(ExecutionPhaseType.PLAN)));
	}

	private OrganizationAsyncRunner runner() {
		return new OrganizationAsyncRunner(organizationExecutor, performanceTelemetryService);
	}

	private OrganizationExecuteRequest request(String source, String target, boolean dryRun) {
		return new OrganizationExecuteRequest(source, target, true, OrganizationLayout.DEFAULT, 100, false, null, true,
				null, null, null, null, false, false, LocationSubdivision.NONE, null, LocationFallbackMode.IGNORE,
				dryRun);
	}
}