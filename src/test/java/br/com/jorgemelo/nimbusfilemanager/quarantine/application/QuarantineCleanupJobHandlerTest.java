package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineCleanupPayload;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * Clearing the records of files that are no longer in quarantine, off the
 * queue. Its own type rather than the purge's: on the executions screen this
 * must not read as the operation that erases files, because it erases none.
 */
class QuarantineCleanupJobHandlerTest {

	private final QuarantinePurgeService quarantinePurgeService = mock(QuarantinePurgeService.class);
	private final ExecutionPayloadCodec executionPayloadCodec = new ExecutionPayloadCodec(new ObjectMapper());
	private final QuarantineCleanupJobHandler handler = new QuarantineCleanupJobHandler(quarantinePurgeService,
			executionPayloadCodec);

	@Test
	void answersForTheCleanupTypeAndNotForThePurge() {
		Assertions.assertThat(handler.type()).isEqualTo(ExecutionType.QUARANTINE_CLEANUP);
	}

	/**
	 * It deletes rows for files that were already gone, so a second pass over the
	 * same shortlist finds the work either done or still to do, and never does it
	 * twice.
	 */
	@Test
	void isResumable() {
		Assertions.assertThat(handler.resumable()).isTrue();
	}

	@Test
	void clearsTheRecordsThePayloadNames() {
		Execution execution = Execution.builder().id(2L).build();

		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());

		handler.handle(execution, claimed(QuarantineConstants.PAYLOAD_SCHEMA_VERSION, ids), ownership);

		verify(quarantinePurgeService).cleanupAbsent(ids, execution, ownership);
	}

	@Test
	void refusesAPayloadWrittenInAShapeItDoesNotKnow() {
		Execution execution = Execution.builder().id(3L).build();

		ClaimedExecution claimed = claimed(QuarantineConstants.PAYLOAD_SCHEMA_VERSION + 1,
				List.of(UUID.randomUUID()));

		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		Assertions.assertThatThrownBy(() -> handler.handle(execution, claimed, ownership))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be run by this version");

		verify(quarantinePurgeService, never()).cleanupAbsent(any(), any(), any());
	}

	/**
	 * A payload with no version at all is not "the current version": it is a row
	 * this build cannot read, and running it would be guessing what it meant.
	 */
	@Test
	void refusesAPayloadThatDoesNotSayWhichShapeItIsIn() {
		Execution execution = Execution.builder().id(5L).build();

		ClaimedExecution claimed = claimed(null, List.of(UUID.randomUUID()));

		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		Assertions.assertThatThrownBy(() -> handler.handle(execution, claimed, ownership))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be run by this version");

		verify(quarantinePurgeService, never()).cleanupAbsent(any(), any(), any());
	}

	/**
	 * A cleanup with no shortlist would be a pass over everything absent right now
	 * - which is a different operation from the one somebody asked for. An absent
	 * list and an empty one are refused alike.
	 */
	@Test
	void refusesAPayloadThatNamesNoRecords() {
		Execution execution = Execution.builder().id(4L).build();

		ExecutionOwnership missingList = mock(ExecutionOwnership.class);

		ClaimedExecution withoutList = claimed(QuarantineConstants.PAYLOAD_SCHEMA_VERSION, null);

		Assertions.assertThatThrownBy(() -> handler.handle(execution, withoutList, missingList))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("has to name the records");

		ClaimedExecution claimed = claimed(QuarantineConstants.PAYLOAD_SCHEMA_VERSION, List.of());

		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		Assertions.assertThatThrownBy(() -> handler.handle(execution, claimed, ownership))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("has to name the records");

		verify(quarantinePurgeService, never()).cleanupAbsent(any(), any(), any());
	}

	private ClaimedExecution claimed(Integer schemaVersion, List<UUID> movementIds) {
		return new ClaimedExecution(1L, ExecutionType.QUARANTINE_CLEANUP.name(), null, null,
				executionPayloadCodec.encode(new QuarantineCleanupPayload(schemaVersion, movementIds)));
	}
}