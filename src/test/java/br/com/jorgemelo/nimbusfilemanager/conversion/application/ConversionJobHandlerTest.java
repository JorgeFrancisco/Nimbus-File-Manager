package br.com.jorgemelo.nimbusfilemanager.conversion.application;

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

import br.com.jorgemelo.nimbusfilemanager.conversion.application.constants.ConversionConstants;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionExecutePayload;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionOptions;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.AudioHandling;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionQuality;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.NameAffixPosition;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.OriginalDisposition;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

@ExtendWith(MockitoExtension.class)
class ConversionJobHandlerTest {

	@Mock
	private VideoConversionService videoConversionService;

	private final ExecutionPayloadCodec executionPayloadCodec = new ExecutionPayloadCodec(new ObjectMapper());

	@Test
	void answersForTheConversionType() {
		Assertions.assertThat(handler().type()).isEqualTo(ExecutionType.CONVERSION);
	}

	/**
	 * The guarantee an {@code AtomicBoolean} in the old runner used to give: a
	 * second concurrent H.265 encode makes both slower rather than either faster.
	 */
	@Test
	void allowsOnlyOneBatchAtATime() {
		Assertions.assertThat(handler().concurrencyLimit()).isEqualTo(1);
	}

	/**
	 * Files have already been placed in the library and originals may already be in
	 * quarantine, so a second pass would start from a library the first one changed.
	 */
	@Test
	void refusesToBeRerunFromTheStartAfterBeingAbandoned() {
		Assertions.assertThat(handler().resumable()).isFalse();
	}

	@Test
	void convertsTheVideosThePayloadNamesWithTheOptionsItCarries() {
		Execution execution = Execution.builder().id(2L).build();

		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());

		handler().handle(execution, claimed(payload(ConversionConstants.EXECUTE_PAYLOAD_SCHEMA_VERSION, ids)),
				ownership);

		verify(videoConversionService).convert(ids, new ConversionOptions(ConversionQuality.HIGH_QUALITY,
				AudioHandling.AAC, OriginalDisposition.QUARANTINE, "_X265", NameAffixPosition.PREFIX), execution,
				ownership);
	}

	@Test
	void refusesAPayloadWrittenInAShapeItDoesNotKnow() {
		Execution execution = Execution.builder().id(3L).build();

		ClaimedExecution claimed = claimed(
				payload(ConversionConstants.EXECUTE_PAYLOAD_SCHEMA_VERSION + 1, List.of(UUID.randomUUID())));

		ConversionJobHandler handler = handler();

		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		Assertions.assertThatThrownBy(() -> handler.handle(execution, claimed, ownership))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be run by this version");

		verify(videoConversionService, never()).convert(any(), any(), any(), any());
	}

	/** Without ids there is nothing to convert, and guessing is not an option. */
	@Test
	void refusesAPayloadThatNamesNoVideos() {
		Execution execution = Execution.builder().id(4L).build();

		ClaimedExecution claimed = claimed(payload(ConversionConstants.EXECUTE_PAYLOAD_SCHEMA_VERSION, List.of()));

		ConversionJobHandler handler = handler();

		ExecutionOwnership ownership = mock(ExecutionOwnership.class);

		Assertions.assertThatThrownBy(() -> handler.handle(execution, claimed, ownership))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("has to name the videos");
	}

	private ConversionJobHandler handler() {
		return new ConversionJobHandler(videoConversionService, executionPayloadCodec);
	}

	private ClaimedExecution claimed(ConversionExecutePayload payload) {
		return new ClaimedExecution(1L, ExecutionType.CONVERSION.name(), "C:\\library", "C:\\library",
				executionPayloadCodec.encode(payload));
	}

	private ConversionExecutePayload payload(Integer schemaVersion, List<UUID> publicIds) {
		return new ConversionExecutePayload(schemaVersion, publicIds, ConversionQuality.HIGH_QUALITY,
				AudioHandling.AAC, OriginalDisposition.QUARANTINE, "_X265", NameAffixPosition.PREFIX);
	}
}