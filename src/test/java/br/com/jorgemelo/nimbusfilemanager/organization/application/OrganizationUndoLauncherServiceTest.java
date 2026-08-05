package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMapper;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.organization.application.constants.OrganizationConstants;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationUndoPayload;
import br.com.jorgemelo.nimbusfilemanager.shared.application.ExecutionLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

@ExtendWith(MockitoExtension.class)
class OrganizationUndoLauncherServiceTest {

	@Mock
	private ExecutionEnqueueService executionEnqueueService;

	private final ExecutionPayloadCodec executionPayloadCodec = new ExecutionPayloadCodec(new ObjectMapper());

	/**
	 * An undo moves from where the files went back to where they came from, so the
	 * pair the worker locks first is the original's two ends the other way round.
	 */
	@Test
	void queuesTheReversalWithTheFoldersSwappedAndTheRunItReverses() {
		Execution undone = Execution.builder().id(9L).executionType(ExecutionType.ORGANIZATION)
				.sourcePath("C:\\input").targetPath("C:\\target").build();

		when(executionEnqueueService.enqueue(any())).thenAnswer(invocation -> {
			Execution queued = invocation.getArgument(0);

			queued.setStatus(ExecutionStatus.PENDING);
			queued.setPublicId(UUID.randomUUID());

			return Optional.of(queued);
		});

		launcher().launch(undone);

		ArgumentCaptor<Execution> captor = ArgumentCaptor.forClass(Execution.class);

		verify(executionEnqueueService).enqueue(captor.capture());

		Execution queued = captor.getValue();

		Assertions.assertThat(queued.getExecutionType()).isEqualTo(ExecutionType.UNDO);
		Assertions.assertThat(queued.getSourcePath()).isEqualTo("C:\\target");
		Assertions.assertThat(queued.getTargetPath()).isEqualTo("C:\\input");

		OrganizationUndoPayload payload = executionPayloadCodec.decode(queued.getRequestPayload(),
				OrganizationUndoPayload.class);

		Assertions.assertThat(payload.schemaVersion()).isEqualTo(OrganizationConstants.UNDO_PAYLOAD_SCHEMA_VERSION);
		Assertions.assertThat(payload.undoneExecutionId()).isEqualTo(9L);
	}

	/**
	 * Only organization and duplicate-quarantine moves are plain source-to-target
	 * movements; anything else has no reversal defined. Refused here, where the
	 * person asking can be told, rather than in a worker minutes later.
	 */
	@Test
	void refusesARunThatHasNoReversalDefined() {
		Execution inventory = Execution.builder().id(1L).executionType(ExecutionType.INVENTORY).build();

		OrganizationUndoLauncherService launcher = launcher();

		Assertions.assertThatThrownBy(() -> launcher.launch(inventory)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("not undoable");

		verify(executionEnqueueService, never()).enqueue(any());
	}

	/**
	 * A quarantined duplicate is reversed by the same movement rows, so the same
	 * reversal serves it.
	 */
	@Test
	void acceptsTheReversalOfADuplicateSentToQuarantine() {
		Execution dedup = Execution.builder().id(2L).executionType(ExecutionType.DEDUP_DELETE).sourcePath("C:\\library")
				.targetPath("C:\\quarantine").build();

		when(executionEnqueueService.enqueue(any())).thenAnswer(invocation -> {
			Execution queued = invocation.getArgument(0);

			queued.setStatus(ExecutionStatus.PENDING);
			queued.setPublicId(UUID.randomUUID());

			return Optional.of(queued);
		});

		Assertions.assertThat(launcher().launch(dedup).executionType()).isEqualTo(ExecutionType.UNDO.name());
	}

	/**
	 * These carry no deduplication key, so a refusal is the queue answering
	 * something nobody has described - and a request that silently vanishes is
	 * worse than one that raises where somebody can be told.
	 */
	@Test
	void raisesWhenTheQueueRefusesARequestThatCannotBeADuplicate() {
		Execution undone = Execution.builder().id(9L).executionType(ExecutionType.ORGANIZATION).sourcePath("C:\\input")
				.targetPath("C:\\target").build();

		when(executionEnqueueService.enqueue(any())).thenReturn(Optional.empty());

		OrganizationUndoLauncherService launcher = launcher();

		Assertions.assertThatThrownBy(() -> launcher.launch(undone)).isInstanceOf(IllegalStateException.class);
	}

	private OrganizationUndoLauncherService launcher() {
		return new OrganizationUndoLauncherService(executionEnqueueService, executionPayloadCodec,
				new ExecutionMapper(new ExecutionMessageCodec(new ObjectMapper()), new ExecutionLabels()),
				new ExecutionMessageCodec(new ObjectMapper()));
	}
}