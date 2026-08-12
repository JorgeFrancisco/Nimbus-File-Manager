package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.Progress;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.organization.application.constants.OrganizationConstants;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecutePayload;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecuteRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;
import br.com.jorgemelo.nimbusfilemanager.shared.application.ExecutionLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * What a queued preview carries, and what the worker makes of it.
 *
 * <p>
 * The point of the pair is that nothing between them is a Java object: the row
 * and its payload are the entire contract, so the worker that runs a preview can
 * be a different process, started after the one that asked for it was closed.
 */
class OrganizationPreviewQueueTest {

	private final ExecutionEnqueueService executionEnqueueService = mock(ExecutionEnqueueService.class);
	private final ExecutionPayloadCodec executionPayloadCodec = new ExecutionPayloadCodec(new ObjectMapper());
	private final OrganizationPreviewJob organizationPreviewJob = mock(OrganizationPreviewJob.class);
	private final OrganizationMetadataRebuild organizationMetadataRebuild = mock(OrganizationMetadataRebuild.class);

	private final OrganizationPreviewLauncher launcher = new OrganizationPreviewLauncher(executionEnqueueService,
			executionPayloadCodec, new ExecutionMapper(new ExecutionMessageCodec(new ObjectMapper()),
					new ExecutionLabels(), Progress.reader(), Progress.estimator()));

	private final OrganizationPreviewJobHandler handler = new OrganizationPreviewJobHandler(organizationPreviewJob,
			organizationMetadataRebuild, executionPayloadCodec);

	@Test
	void aQueuedPreviewIsItsOwnTypeAndSaysItMovesNothing() {
		when(executionEnqueueService.enqueue(any())).thenAnswer(call -> {
			Execution queued = call.getArgument(0);

			queued.setStatus(ExecutionStatus.PENDING);

			return Optional.of(queued);
		});

		ArgumentCaptor<Execution> queued = ArgumentCaptor.forClass(Execution.class);

		launcher.launch(request());

		verify(executionEnqueueService).enqueue(queued.capture());

		Assertions.assertThat(queued.getValue().getExecutionType()).isEqualTo(ExecutionType.ORGANIZATION_PREVIEW);
		Assertions.assertThat(queued.getValue().getExecuteFlag()).isFalse();
		Assertions.assertThat(queued.getValue().getSourcePath()).contains("input");
		Assertions.assertThat(queued.getValue().getTargetPath()).contains("target");

		// Previews are not deduplicated, like the runs they describe: two requests over
		// the same folders are two questions somebody asked.
		Assertions.assertThat(queued.getValue().getDedupKey()).isNull();
	}

	@Test
	void thePayloadCarriesEveryOptionTheRowCannotHold() {
		when(executionEnqueueService.enqueue(any())).thenAnswer(call -> {
			Execution queued = call.getArgument(0);

			queued.setStatus(ExecutionStatus.PENDING);

			return Optional.of(queued);
		});

		ArgumentCaptor<Execution> queued = ArgumentCaptor.forClass(Execution.class);

		launcher.launch(request());

		verify(executionEnqueueService).enqueue(queued.capture());

		OrganizationExecutePayload payload = executionPayloadCodec.decode(queued.getValue().getRequestPayload(),
				OrganizationExecutePayload.class);

		Assertions.assertThat(payload.schemaVersion())
				.isEqualTo(OrganizationConstants.EXECUTE_PAYLOAD_SCHEMA_VERSION);
		Assertions.assertThat(payload.layout()).isEqualTo(OrganizationLayout.YEAR_MONTH_DAY);
		Assertions.assertThat(payload.limit()).isEqualTo(50);
	}

	/**
	 * A preview is safe to run again from the start, and says so. It writes only
	 * rows nobody can see until they are published, so a second attempt cannot be
	 * told apart from a first - unlike the run it describes, which has already
	 * moved half the library by the time anybody notices it stopped.
	 */
	@Test
	void aPreviewIsResumableAndRunsOneAtATime() {
		Assertions.assertThat(handler.type()).isEqualTo(ExecutionType.ORGANIZATION_PREVIEW);
		Assertions.assertThat(handler.resumable()).isTrue();
		Assertions.assertThat(handler.concurrencyLimit()).isEqualTo(1);
	}

	/**
	 * The worker rebuilds the request from the row and the payload - never from
	 * anything the process that queued it left behind.
	 */
	@Test
	void theWorkerRebuildsTheRequestFromTheRowAndItsPayload() {
		Execution execution = Execution.builder().id(42L).recursive(true).build();

		handler.handle(execution, claimed(payload(OrganizationConstants.EXECUTE_PAYLOAD_SCHEMA_VERSION)), null);

		ArgumentCaptor<OrganizationExecuteRequest> ran = ArgumentCaptor.forClass(OrganizationExecuteRequest.class);

		verify(organizationPreviewJob).run(ran.capture(), any(), any());

		Assertions.assertThat(ran.getValue().source().toString()).contains("input");
		Assertions.assertThat(ran.getValue().target().toString()).contains("target");
		Assertions.assertThat(ran.getValue().dryRunValue()).isTrue();
		Assertions.assertThat(ran.getValue().layout()).isEqualTo(OrganizationLayout.YEAR_MONTH_DAY);
	}

	@Test
	void metadataIsRebuiltBeforeTheWorkerPlansAnything() {
		Execution execution = Execution.builder().id(42L).recursive(true).build();

		handler.handle(execution, claimed(payload(OrganizationConstants.EXECUTE_PAYLOAD_SCHEMA_VERSION)), null);

		verify(organizationMetadataRebuild).beforePlanning(any());
	}

	/**
	 * A payload written in a shape this version does not know is refused rather
	 * than read as far as it goes: a half-understood option would describe a plan
	 * nobody asked for, and somebody would decide on it.
	 */
	@Test
	void aPayloadFromAnotherSchemaIsRefused() {
		Execution execution = Execution.builder().id(42L).recursive(true).build();

		ClaimedExecution claimed = claimed(payload(99));

		Assertions.assertThatThrownBy(() -> handler.handle(execution, claimed, null))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schema");

		verify(organizationPreviewJob, never()).run(any(), any(), any());
	}

	private OrganizationExecuteRequest request() {
		return new OrganizationExecuteRequest("C:/input", "C:/target", true, OrganizationLayout.YEAR_MONTH_DAY, 50,
				false, null, null, null, null, null, null, true, false);
	}

	private String payload(int schemaVersion) {
		return executionPayloadCodec.encode(new OrganizationExecutePayload(schemaVersion,
				OrganizationLayout.YEAR_MONTH_DAY, 50, false, null, null, null, null, null, null, true, false, null,
				null, null));
	}

	private ClaimedExecution claimed(String payload) {
		return new ClaimedExecution(42L, ExecutionType.ORGANIZATION_PREVIEW.name(), "C:/input", "C:/target", payload);
	}
}