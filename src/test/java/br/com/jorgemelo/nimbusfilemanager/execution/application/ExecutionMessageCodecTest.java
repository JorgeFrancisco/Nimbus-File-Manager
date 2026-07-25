package br.com.jorgemelo.nimbusfilemanager.execution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class ExecutionMessageCodecTest {

	private final ExecutionMessageCodec codec = new ExecutionMessageCodec(new ObjectMapper());

	@Test
	void encodesEmptyOrNullArgumentsAsNull() {
		assertThat(codec.encode(List.of())).isNull();
		assertThat(codec.encode(null)).isNull();
	}

	@Test
	void roundTripsMixedStringAndNumberArguments() {
		String json = codec.encode(List.of("C:/media/photo.jpg", 5L, 2L, 1L));

		assertThat(json).isEqualTo("[\"C:/media/photo.jpg\",5,2,1]");

		Object[] decoded = codec.decode(json);

		assertThat(decoded).containsExactly("C:/media/photo.jpg", 5, 2, 1);
	}

	@Test
	void decodesNullOrBlankToAnEmptyArray() {
		assertThat(codec.decode(null)).isEmpty();
		assertThat(codec.decode("   ")).isEmpty();
	}

	@Test
	void raisesWhenDecodingMalformedJson() {
		assertThatIllegalStateException().isThrownBy(() -> codec.decode("{not a json array"))
				.withMessageContaining("Could not decode execution message arguments");
	}

	@Test
	void raisesWhenArgumentsCannotBeSerialized() {
		// A bare Object has no serializable properties: Jackson fails on the empty
		// bean, which the codec must surface as an IllegalStateException.
		assertThatIllegalStateException().isThrownBy(() -> codec.encode(List.of(new Object())))
				.withMessageContaining("Could not encode execution message arguments");
	}
}