package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.EligibilityAnnouncer;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.persistence.SimilarityPurgeWriter;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantinePurgePayload;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantinePurgeResult;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

@ExtendWith(MockitoExtension.class)
class QuarantinePurgeJobHandlerTest {

	@Mock
	private QuarantinePurgeService quarantinePurgeService;

	private final ExecutionPayloadCodec executionPayloadCodec = new ExecutionPayloadCodec(new ObjectMapper());
	private final SimilarityPurgeWriter similarityPurgeWriter = mock(SimilarityPurgeWriter.class);
	private final EligibilityAnnouncer eligibilityAnnouncer = mock(EligibilityAnnouncer.class);

	@Test
	void answersForThePurgeType() {
		Assertions.assertThat(handler().type()).isEqualTo(ExecutionType.QUARANTINE_PURGE);
	}

	@Test
	void allowsOnlyOnePurgeAtATime() {
		Assertions.assertThat(handler().concurrencyLimit()).isEqualTo(1);
	}

	/** The one operation with nothing to undo, so nothing to start over. */
	@Test
	void refusesToBeRerunFromTheStartAfterBeingAbandoned() {
		Assertions.assertThat(handler().resumable()).isFalse();
	}

	@Test
	void expungesTheItemsAPersonPicked() {
		Execution execution = Execution.builder().id(2L).build();

		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		List<UUID> ids = List.of(UUID.randomUUID());

		when(quarantinePurgeService.purgeSelected(ids, execution, ownership)).thenReturn(freed(0));

		handler().handle(execution, claimed(QuarantineConstants.PAYLOAD_SCHEMA_VERSION, null, ids), ownership);

		verify(quarantinePurgeService).purgeSelected(ids, execution, ownership);
		verify(quarantinePurgeService, never()).purgeOlderThan(anyInt(), any(), any());
	}

	/**
	 * The daily pass carries only its window: what is overdue is decided now, so a
	 * request that waited behind a long conversion never expunges by yesterday's
	 * clock.
	 */
	@Test
	void expungesByTheWindowWhenNoItemWasNamed() {
		Execution execution = Execution.builder().id(3L).build();

		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		when(quarantinePurgeService.purgeOlderThan(anyInt(), any(), any())).thenReturn(freed(0));

		handler().handle(execution, claimed(QuarantineConstants.PAYLOAD_SCHEMA_VERSION, 90, null), ownership);

		verify(quarantinePurgeService).purgeOlderThan(90, execution, ownership);
		verify(quarantinePurgeService, never()).purgeSelected(any(), any(), any());
	}

	@Test
	void refusesAPayloadWrittenInAShapeItDoesNotKnow() {
		Execution execution = Execution.builder().id(4L).build();

		ClaimedExecution claimed = claimed(QuarantineConstants.PAYLOAD_SCHEMA_VERSION + 1, 90, null);

		QuarantinePurgeJobHandler handler = handler();

		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		Assertions.assertThatThrownBy(() -> handler.handle(execution, claimed, ownership))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be run by this version");

		verify(quarantinePurgeService, never()).purgeOlderThan(anyInt(), any(), any());
	}

	/** Neither shape is set: expunging everything is not the fallback. */
	@Test
	void refusesAPayloadThatNamesNeitherItemsNorAWindow() {
		Execution execution = Execution.builder().id(5L).build();

		ClaimedExecution claimed = claimed(QuarantineConstants.PAYLOAD_SCHEMA_VERSION, null, List.of());

		QuarantinePurgeJobHandler handler = handler();

		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		Assertions.assertThatThrownBy(() -> handler.handle(execution, claimed, ownership))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("either the items or a window");

		verify(quarantinePurgeService, never()).purgeSelected(any(), any(), any());
	}

	private QuarantinePurgeJobHandler handler() {
		return new QuarantinePurgeJobHandler(quarantinePurgeService, executionPayloadCodec, similarityPurgeWriter, eligibilityAnnouncer);
	}

	private QuarantinePurgeResult freed(int catalogsFreed) {
		return new QuarantinePurgeResult(0, 0, catalogsFreed, 0, 0, 0);
	}

	private ClaimedExecution claimed(Integer schemaVersion, Integer retentionDays, List<UUID> movementIds) {
		return new ClaimedExecution(1L, ExecutionType.QUARANTINE_PURGE.name(), "C:\\quarantine", "C:\\quarantine",
				executionPayloadCodec.encode(new QuarantinePurgePayload(schemaVersion, retentionDays, movementIds)));
	}
}