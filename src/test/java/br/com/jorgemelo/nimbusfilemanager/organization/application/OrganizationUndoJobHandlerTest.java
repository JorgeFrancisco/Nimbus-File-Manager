package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.organization.application.constants.OrganizationConstants;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationUndoPayload;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

@ExtendWith(MockitoExtension.class)
class OrganizationUndoJobHandlerTest {

	@Mock
	private OrganizationUndoService organizationUndoService;

	private final ExecutionPayloadCodec executionPayloadCodec = new ExecutionPayloadCodec(new ObjectMapper());

	@Test
	void answersForTheUndoType() {
		Assertions.assertThat(handler().type()).isEqualTo(ExecutionType.UNDO);
	}

	/**
	 * A reversal that stopped halfway put files back that its movements still
	 * describe as needing to be put back. Running it again from the start would
	 * find them home, see the destination occupied, and record errors for work that
	 * succeeded.
	 */
	@Test
	void refusesToBeRerunFromTheStartAfterBeingAbandoned() {
		Assertions.assertThat(handler().resumable()).isFalse();
	}

	@Test
	void allowsOnlyOneReversalAtATime() {
		Assertions.assertThat(handler().concurrencyLimit()).isEqualTo(1);
	}

	@Test
	void reversesTheRunTheRowNames() {
		Execution execution = Execution.builder().id(2L).build();

		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		handler().handle(execution, claimed(payload(OrganizationConstants.UNDO_PAYLOAD_SCHEMA_VERSION, 9L)), ownership);

		verify(organizationUndoService).undo(9L, execution, ownership);
	}

	@Test
	void refusesAPayloadWrittenInAShapeItDoesNotKnow() {
		Execution execution = Execution.builder().id(3L).build();

		ClaimedExecution claimed = claimed(payload(OrganizationConstants.UNDO_PAYLOAD_SCHEMA_VERSION + 1, 9L));

		OrganizationUndoJobHandler handler = handler();

		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		Assertions.assertThatThrownBy(() -> handler.handle(execution, claimed, ownership))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be run by this version");

		verify(organizationUndoService, never()).undo(anyLong(), any(), any());
	}

	/**
	 * Without the run it reverses there is nothing to read movements from, and
	 * guessing would mean undoing something nobody asked about.
	 */
	@Test
	void refusesAPayloadThatNamesNoRun() {
		Execution execution = Execution.builder().id(4L).build();

		ClaimedExecution claimed = claimed(payload(OrganizationConstants.UNDO_PAYLOAD_SCHEMA_VERSION, null));

		OrganizationUndoJobHandler handler = handler();

		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		Assertions.assertThatThrownBy(() -> handler.handle(execution, claimed, ownership))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("has to name the execution");
	}

	private OrganizationUndoJobHandler handler() {
		return new OrganizationUndoJobHandler(organizationUndoService, executionPayloadCodec);
	}

	private ClaimedExecution claimed(OrganizationUndoPayload payload) {
		return new ClaimedExecution(1L, ExecutionType.UNDO.name(), "C:\\target", "C:\\input",
				executionPayloadCodec.encode(payload));
	}

	private OrganizationUndoPayload payload(Integer schemaVersion, Long undoneExecutionId) {
		return new OrganizationUndoPayload(schemaVersion, undoneExecutionId);
	}
}