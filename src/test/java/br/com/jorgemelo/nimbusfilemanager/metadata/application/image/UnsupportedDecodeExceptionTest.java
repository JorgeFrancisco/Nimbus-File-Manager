package br.com.jorgemelo.nimbusfilemanager.metadata.application.image;

import java.io.IOException;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class UnsupportedDecodeExceptionTest {

	@Test
	void shouldCarryTheMessageAloneWhenThereIsNoUnderlyingCause() {
		UnsupportedDecodeException exception = new UnsupportedDecodeException("No reader for HEIC");

		Assertions.assertThat(exception).hasMessage("No reader for HEIC").hasNoCause();
	}

	/**
	 * The decoder wraps the reader failure so the caller can still tell an
	 * unsupported format from a corrupt file of a supported one.
	 */
	@Test
	void shouldKeepTheUnderlyingCauseWhenOneIsGiven() {
		IOException cause = new IOException("no plugin");

		UnsupportedDecodeException exception = new UnsupportedDecodeException("No reader for HEIC", cause);

		Assertions.assertThat(exception).hasMessage("No reader for HEIC").hasCause(cause);
	}
}