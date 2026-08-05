package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Consumer;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPlan;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationSummary;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * The order in which a preview becomes readable, which is the protocol itself.
 *
 * <p>
 * The plan is written the moment it exists, invisibly; the dry run then walks
 * every item applying the same read-only checks a real run applies; only when
 * that finishes is the plan published. Anything that goes wrong in between
 * leaves rows nobody can see.
 */
class OrganizationPreviewJobTest {

	private final OrganizationExecutor organizationExecutor = mock(OrganizationExecutor.class);
	private final OrganizationPlanWriter organizationPlanWriter = mock(OrganizationPlanWriter.class);

	private final OrganizationPreviewJob job = new OrganizationPreviewJob(organizationExecutor,
			organizationPlanWriter);

	private final Execution execution = Execution.builder().id(42L).build();

	private final ExecutionOwnership ownership = Takings.owning(42L);

	@Test
	void thePlanIsWrittenBeforeTheDryRunAndPublishedOnlyAfterIt() {
		whenTheExecutorPlans();
		when(organizationPlanWriter.publish(anyLong(), any())).thenReturn(true);

		job.run(request(), execution, ownership);

		InOrder order = inOrder(organizationPlanWriter);

		order.verify(organizationPlanWriter).build(eq(42L), any());
		order.verify(organizationPlanWriter).publish(eq(42L), any());

		verify(organizationPlanWriter, never()).markFailed(anyLong());
	}

	/**
	 * The preview writes nothing to the library, and the executor's own gate blocks
	 * every side effect - but it writes the execution row, so the taking travels
	 * with it and the executor is handed the same one the worker is running under.
	 */
	@Test
	void thePreviewCarriesTheTakingItsRowIsWrittenUnder() {
		whenTheExecutorPlans();
		when(organizationPlanWriter.publish(anyLong(), any())).thenReturn(true);

		job.run(request(), execution, ownership);

		verify(organizationExecutor).execute(any(), eq(execution), eq(ownership), any());
	}

	@Test
	void aPreviewThatDiesLeavesItsPlanUnpublishedAndMarkedFailed() {
		when(organizationExecutor.execute(any(), any(), any(), any())).thenAnswer(call -> {
			plannedBy(call.getArgument(3));

			throw new IllegalStateException("the disk went away");
		});

		OrganizationExecuteRequest request = request();

		Assertions.assertThatThrownBy(() -> job.run(request, execution, ownership))
				.isInstanceOf(IllegalStateException.class);

		verify(organizationPlanWriter).build(eq(42L), any());
		verify(organizationPlanWriter).markFailed(42L);
		verify(organizationPlanWriter, never()).publish(anyLong(), any());
	}

	/**
	 * A run rejected before it planned anything wrote nothing, so there is nothing
	 * to fail and nothing to publish. Marking a plan that was never opened would
	 * be updating a row that does not exist.
	 */
	@Test
	void aRunThatNeverPlannedPublishesNothingAndFailsNothing() {
		when(organizationExecutor.execute(any(), any(), any(), any())).thenReturn(null);

		job.run(request(), execution, ownership);

		verify(organizationPlanWriter, never()).build(anyLong(), any());
		verify(organizationPlanWriter, never()).publish(anyLong(), any());
		verify(organizationPlanWriter, never()).markFailed(anyLong());
	}

	/**
	 * A run that fails before it plans anything has nothing to mark: marking a plan
	 * that was never opened would be updating a row that does not exist.
	 */
	@Test
	void aRunThatFailsBeforePlanningMarksNothing() {
		when(organizationExecutor.execute(any(), any(), any(), any()))
				.thenThrow(new IllegalStateException("the folder went away"));

		OrganizationExecuteRequest request = request();

		Assertions.assertThatThrownBy(() -> job.run(request, execution, ownership))
				.isInstanceOf(IllegalStateException.class);

		verify(organizationPlanWriter, never()).build(anyLong(), any());
		verify(organizationPlanWriter, never()).markFailed(anyLong());
	}

	/**
	 * Losing the publication race is reported, not retried: another attempt already
	 * decided this plan's fate, and publishing over it would replace what somebody
	 * may already be reading.
	 */
	@Test
	void losingThePublicationRaceDoesNotThrow() {
		whenTheExecutorPlans();
		when(organizationPlanWriter.publish(anyLong(), any())).thenReturn(false);

		Assertions.assertThatCode(() -> job.run(request(), execution, ownership)).doesNotThrowAnyException();
	}

	private void whenTheExecutorPlans() {
		when(organizationExecutor.execute(any(), any(), any(), any())).thenAnswer(call -> {
			plannedBy(call.getArgument(3));

			return null;
		});
	}

	@SuppressWarnings("unchecked")
	private void plannedBy(Object sink) {
		((Consumer<OrganizationPlan>) sink).accept(plan());
	}

	private OrganizationExecuteRequest request() {
		return new OrganizationExecuteRequest("C:/input", "C:/target", true, OrganizationLayout.DEFAULT, 50, false,
				null, null, null, null, null, null, true, false);
	}

	private OrganizationPlan plan() {
		return new OrganizationPlan("C:/input", "C:/target", OrganizationLayout.DEFAULT, false,
				new OrganizationSummary(1, 1, 0, 0, 1, 100, 0, 0, 0), List.of());
	}
}