package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Which verdicts a retry is allowed to undo.
 *
 * <p>
 * The list used to be written by hand, naming {@code UNKNOWN} alone, and the
 * day a second non-terminal reason existed the button silently stopped covering
 * it - files whose ffmpeg never started stayed written off with nothing able to
 * return them to the queue. Deriving it is what keeps the two from drifting.
 */
class FingerprintFailureReasonTest {

	@Test
	void everyReasonThatIsNotTerminalCanBeRetried() {
		Assertions.assertThat(FingerprintFailureReason.retryable())
				.containsExactlyInAnyOrder(FingerprintFailureReason.TOOL_UNAVAILABLE, FingerprintFailureReason.UNKNOWN)
				.allMatch(reason -> !reason.terminal());
	}

	/**
	 * A verdict about the bytes on disk stays: the button never promises to fix a
	 * file that nothing can fix.
	 */
	@Test
	void aVerdictAboutTheFileItselfIsNeverRetried() {
		Assertions.assertThat(FingerprintFailureReason.retryable()).doesNotContain(
				FingerprintFailureReason.CORRUPTED_FILE, FingerprintFailureReason.NOT_AN_IMAGE,
				FingerprintFailureReason.UNSUPPORTED_FORMAT, FingerprintFailureReason.DECODER_REFUSED);
	}
}