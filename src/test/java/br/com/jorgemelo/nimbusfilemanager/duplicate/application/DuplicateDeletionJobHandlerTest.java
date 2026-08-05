package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateDeletePayload;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

@ExtendWith(MockitoExtension.class)
class DuplicateDeletionJobHandlerTest {

	@Mock
	private DuplicateDeletionService duplicateDeletionService;

	private final ExecutionPayloadCodec executionPayloadCodec = new ExecutionPayloadCodec(new ObjectMapper());

	@Test
	void answersForTheDeduplicationType() {
		Assertions.assertThat(handler().type()).isEqualTo(ExecutionType.DEDUP_DELETE);
	}

	@Test
	void allowsOnlyOneDeletionAtATime() {
		Assertions.assertThat(handler().concurrencyLimit()).isEqualTo(1);
	}

	/** Files have already left the library by the time anybody notices. */
	@Test
	void refusesToBeRerunFromTheStartAfterBeingAbandoned() {
		Assertions.assertThat(handler().resumable()).isFalse();
	}

	@Test
	void quarantinesTheFilesThePayloadNames() {
		Execution execution = Execution.builder().id(2L).build();

		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());

		handler().handle(execution, claimed(DuplicateConstants.DELETE_PAYLOAD_SCHEMA_VERSION, ids), ownership);

		verify(duplicateDeletionService).delete(ids, execution, ownership);
	}

	@Test
	void refusesAPayloadWrittenInAShapeItDoesNotKnow() {
		Execution execution = Execution.builder().id(3L).build();

		ClaimedExecution claimed = claimed(DuplicateConstants.DELETE_PAYLOAD_SCHEMA_VERSION + 1,
				List.of(UUID.randomUUID()));

		DuplicateDeletionJobHandler handler = handler();

		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		Assertions.assertThatThrownBy(() -> handler.handle(execution, claimed, ownership))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be run by this version");

		verify(duplicateDeletionService, never()).delete(any(), any(), any());
	}

	@Test
	void refusesAPayloadThatNamesNoFiles() {
		Execution execution = Execution.builder().id(4L).build();

		ClaimedExecution claimed = claimed(DuplicateConstants.DELETE_PAYLOAD_SCHEMA_VERSION, List.of());

		DuplicateDeletionJobHandler handler = handler();

		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		Assertions.assertThatThrownBy(() -> handler.handle(execution, claimed, ownership))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("has to name the files");
	}

	private DuplicateDeletionJobHandler handler() {
		return new DuplicateDeletionJobHandler(duplicateDeletionService, executionPayloadCodec);
	}

	private ClaimedExecution claimed(Integer schemaVersion, List<UUID> publicIds) {
		return new ClaimedExecution(1L, ExecutionType.DEDUP_DELETE.name(), "C:\\quarantine", "C:\\quarantine",
				executionPayloadCodec.encode(new DuplicateDeletePayload(schemaVersion, publicIds)));
	}
}