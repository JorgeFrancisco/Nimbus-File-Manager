package br.com.jorgemelo.nimbusfilemanager.timeline.application;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineCursor;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineUndatedCursor;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.enums.TimelineMediaType;

/**
 * Round-trip and rejection behaviour of the base64/JSON timeline cursor codec
 * (dated and undated), including tampered payloads and wrong media types.
 */
class TimelineCursorCodecTest {

	private final TimelineCursorCodec codec = new TimelineCursorCodec(new ObjectMapper().findAndRegisterModules());

	@Test
	void encodesAndDecodesDatedCursorRoundTrip() {
		TimelineCursor cursor = new TimelineCursor(LocalDateTime.parse("2026-07-12T10:15:00"), 42L,
				TimelineMediaType.PHOTO);

		String encoded = codec.encode(cursor);

		TimelineCursor decoded = codec.decode(encoded, TimelineMediaType.PHOTO);

		Assertions.assertThat(decoded).isEqualTo(cursor);
	}

	@Test
	void decodeRejectsCursorOfDifferentMediaType() {
		String encoded = codec
				.encode(new TimelineCursor(LocalDateTime.parse("2026-07-12T10:15:00"), 42L, TimelineMediaType.PHOTO));

		Assertions.assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(encoded, TimelineMediaType.VIDEO))
				.withMessageContaining("Invalid Timeline cursor");
	}

	@Test
	void decodeRejectsCursorWithNonPositiveInternalId() {
		String encoded = codec
				.encode(new TimelineCursor(LocalDateTime.parse("2026-07-12T10:15:00"), 0L, TimelineMediaType.ALL));

		Assertions.assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(encoded, TimelineMediaType.ALL));
	}

	@Test
	void decodeRejectsCursorWithNullCaptureDate() {
		String encoded = codec.encode(new TimelineCursor(null, 5L, TimelineMediaType.ALL));

		Assertions.assertThatIllegalArgumentException().isThrownBy(() -> codec.decode(encoded, TimelineMediaType.ALL));
	}

	@Test
	void decodeRejectsMalformedBase64() {
		Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> codec.decode("!!!not-base64!!!", TimelineMediaType.ALL));
	}

	@Test
	void decodeRejectsValidBase64ThatIsNotACursor() {
		String notACursor = Base64.getUrlEncoder().withoutPadding()
				.encodeToString("{\"unexpected\":true}".getBytes(StandardCharsets.UTF_8));

		Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> codec.decode(notACursor, TimelineMediaType.ALL));
	}

	@Test
	void encodesAndDecodesUndatedCursorRoundTrip() {
		TimelineUndatedCursor cursor = new TimelineUndatedCursor(7L, TimelineMediaType.VIDEO);

		String encoded = codec.encodeUndated(cursor);

		TimelineUndatedCursor decoded = codec.decodeUndated(encoded, TimelineMediaType.VIDEO);

		Assertions.assertThat(decoded).isEqualTo(cursor);
	}

	@Test
	void decodeUndatedRejectsWrongTypeOrInvalidId() {
		String wrongType = codec.encodeUndated(new TimelineUndatedCursor(7L, TimelineMediaType.VIDEO));

		Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> codec.decodeUndated(wrongType, TimelineMediaType.PHOTO));

		String invalidId = codec.encodeUndated(new TimelineUndatedCursor(0L, TimelineMediaType.PHOTO));

		Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> codec.decodeUndated(invalidId, TimelineMediaType.PHOTO));
	}

	/**
	 * A cursor that cannot be serialised is a programming fault, not bad user input:
	 * it has to surface as an illegal state, never as a cursor the screen retries.
	 */
	@Test
	void encodeFailureSurfacesAsAnIllegalStateAndNotAsAnInvalidCursor() throws Exception {
		ObjectMapper failing = mock(ObjectMapper.class);

		when(failing.writeValueAsBytes(any())).thenThrow(new JsonProcessingExceptionStub());

		TimelineCursorCodec broken = new TimelineCursorCodec(failing);

		TimelineCursor cursor = new TimelineCursor(LocalDateTime.parse("2026-07-12T10:15:00"), 42L,
				TimelineMediaType.PHOTO);

		Assertions.assertThatThrownBy(() -> broken.encode(cursor)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Could not encode");
	}

	/** Same contract for the undated cursor, which the screen also round-trips. */
	@Test
	void encodeUndatedFailureSurfacesAsAnIllegalState() throws Exception {
		ObjectMapper failing = mock(ObjectMapper.class);

		when(failing.writeValueAsBytes(any())).thenThrow(new JsonProcessingExceptionStub());

		TimelineCursorCodec broken = new TimelineCursorCodec(failing);

		TimelineUndatedCursor cursor = new TimelineUndatedCursor(7L, TimelineMediaType.ALL);

		Assertions.assertThatThrownBy(() -> broken.encodeUndated(cursor)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Could not encode");
	}

	/** Jackson's checked failure, with no real serialisation problem behind. */
	private static final class JsonProcessingExceptionStub extends JsonProcessingException {

		private static final long serialVersionUID = 1L;

		private JsonProcessingExceptionStub() {
			super("boom");
		}
	}
}