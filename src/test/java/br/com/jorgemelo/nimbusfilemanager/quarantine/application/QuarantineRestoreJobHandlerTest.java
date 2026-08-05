package br.com.jorgemelo.nimbusfilemanager.quarantine.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.constants.QuarantineConstants;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto.QuarantineRestorePayload;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

@ExtendWith(MockitoExtension.class)
class QuarantineRestoreJobHandlerTest {

	@Mock
	private QuarantineService quarantineService;

	private final ExecutionPayloadCodec executionPayloadCodec = new ExecutionPayloadCodec(new ObjectMapper());

	@Test
	void answersForTheRestoreType() {
		Assertions.assertThat(handler().type()).isEqualTo(ExecutionType.QUARANTINE_RESTORE);
	}

	@Test
	void allowsOnlyOneRestoreAtATime() {
		Assertions.assertThat(handler().concurrencyLimit()).isEqualTo(1);
	}

	/** Files have already left quarantine by the time anybody notices. */
	@Test
	void refusesToBeRerunFromTheStartAfterBeingAbandoned() {
		Assertions.assertThat(handler().resumable()).isFalse();
	}

	/**
	 * A selection carries no destination: each item goes back to its own origin.
	 */
	@Test
	void putsBackTheItemsThePayloadNames() {
		Execution execution = Execution.builder().id(2L).build();

		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());

		handler().handle(execution, claimed(QuarantineConstants.PAYLOAD_SCHEMA_VERSION, ids, null), ownership);

		verify(quarantineService).restoreMany(ids, null, execution, ownership);
	}

	/**
	 * The single restore arrives with the destination its conversation settled on,
	 * and the worker carries it out rather than deciding anything of its own.
	 */
	@Test
	void restoresOneItemToTheDestinationThatWasDecided(@TempDir Path folder) {
		Execution execution = Execution.builder().id(5L).build();

		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		UUID movementId = UUID.randomUUID();

		Path destination = folder.resolve("pictures").resolve("a.jpg");

		handler().handle(execution, claimed(QuarantineConstants.PAYLOAD_SCHEMA_VERSION, List.of(movementId),
				PathUtils.normalize(destination)), ownership);

		verify(quarantineService).restoreMany(List.of(movementId), destination, execution, ownership);
	}

	/**
	 * One destination belongs to one file. Applied to a selection it would send
	 * every item to the same path, each overwriting the last - so a payload shaped
	 * that way is refused rather than interpreted.
	 */
	@Test
	void refusesADecidedDestinationForMoreThanOneItem(@TempDir Path folder) {
		Execution execution = Execution.builder().id(6L).build();

		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		ClaimedExecution claimed = claimed(QuarantineConstants.PAYLOAD_SCHEMA_VERSION,
				List.of(UUID.randomUUID(), UUID.randomUUID()), PathUtils.normalize(folder.resolve("a.jpg")));

		QuarantineRestoreJobHandler handler = handler();

		Assertions.assertThatThrownBy(() -> handler.handle(execution, claimed, ownership))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("restores exactly one item");

		verify(quarantineService, never()).restoreMany(any(), any(), any(), any());
	}

	@Test
	void refusesAPayloadWrittenInAShapeItDoesNotKnow() {
		Execution execution = Execution.builder().id(3L).build();

		ClaimedExecution claimed = claimed(QuarantineConstants.PAYLOAD_SCHEMA_VERSION + 1,
				List.of(UUID.randomUUID()), null);

		QuarantineRestoreJobHandler handler = handler();

		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		Assertions.assertThatThrownBy(() -> handler.handle(execution, claimed, ownership))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be run by this version");

		verify(quarantineService, never()).restoreMany(any(), any(), any(), any());
	}

	/**
	 * A payload with no version at all is not "the current version": it is a row
	 * this build cannot read, and running it would be guessing what it meant.
	 */
	@Test
	void refusesAPayloadThatDoesNotSayWhichShapeItIsIn() {
		Execution execution = Execution.builder().id(7L).build();

		ClaimedExecution claimed = claimed(null, List.of(UUID.randomUUID()), null);

		QuarantineRestoreJobHandler handler = handler();

		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		Assertions.assertThatThrownBy(() -> handler.handle(execution, claimed, ownership))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be run by this version");

		verify(quarantineService, never()).restoreMany(any(), any(), any(), any());
	}

	/** An absent list of items and an empty one are refused alike. */
	@Test
	void refusesAPayloadThatNamesNoItems() {
		Execution execution = Execution.builder().id(4L).build();

		QuarantineRestoreJobHandler handler = handler();

		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		ClaimedExecution withoutList = claimed(QuarantineConstants.PAYLOAD_SCHEMA_VERSION, null, null);

		Assertions.assertThatThrownBy(() -> handler.handle(execution, withoutList, ownership))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("has to name the items");

		ClaimedExecution claimed = claimed(QuarantineConstants.PAYLOAD_SCHEMA_VERSION, List.of(), null);

		Assertions.assertThatThrownBy(() -> handler.handle(execution, claimed, ownership))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("has to name the items");

		verify(quarantineService, never()).restoreMany(any(), any(), any(), any());
	}

	private QuarantineRestoreJobHandler handler() {
		return new QuarantineRestoreJobHandler(quarantineService, executionPayloadCodec);
	}

	private ClaimedExecution claimed(Integer schemaVersion, List<UUID> movementIds, String destination) {
		return new ClaimedExecution(1L, ExecutionType.QUARANTINE_RESTORE.name(), "C:\\quarantine", "C:\\quarantine",
				executionPayloadCodec.encode(new QuarantineRestorePayload(schemaVersion, movementIds, destination)));
	}
}