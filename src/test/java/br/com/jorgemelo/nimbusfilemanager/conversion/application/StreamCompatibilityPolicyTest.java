package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.AudioHandling;

class StreamCompatibilityPolicyTest {

	private final StreamCompatibilityPolicy policy = new StreamCompatibilityPolicy();

	@Test
	void retriesWithAacWhenMp4RefusedTheCopiedAudioStream() {
		Assertions.assertThat(policy.shouldRetryWithAac(AudioHandling.AUTO,
				"[mp4 @ 000001] Could not find tag for codec pcm_s16le in stream #1")).isTrue();
		Assertions.assertThat(policy.shouldRetryWithAac(AudioHandling.AUTO,
				"Could not write header for output file #0 (incorrect codec parameters ?)")).isTrue();
	}

	@Test
	void doesNotRetryForAFailureAnotherAudioCodecCannotFix() {
		Assertions.assertThat(policy.shouldRetryWithAac(AudioHandling.AUTO, "No space left on device")).isFalse();
		Assertions.assertThat(policy.shouldRetryWithAac(AudioHandling.AUTO, "Invalid data found when processing input"))
				.isFalse();
	}

	@Test
	void neverRetriesWithAacWhenTheUserPinnedTheAudioHandling() {
		String error = "Could not find tag for codec pcm_s16le";

		Assertions.assertThat(policy.shouldRetryWithAac(AudioHandling.COPY, error)).isFalse();
		Assertions.assertThat(policy.shouldRetryWithAac(AudioHandling.AAC, error)).isFalse();
	}

	@Test
	void dropsTheSubtitlesWhenTheyAreWhatMp4RefusedNotTheAudio() {
		String error = "[mp4 @ 000001] Could not find tag for codec hdmv_pgs_subtitle in stream #2, "
				+ "codec not currently supported in container";

		Assertions.assertThat(policy.shouldRetryWithoutSubtitles(error)).isTrue();

		// The same error must not be mistaken for an audio problem, or the file would
		// be re-encoded once for nothing before the subtitles are dropped.
		Assertions.assertThat(policy.shouldRetryWithAac(AudioHandling.AUTO, error)).isFalse();
	}

	@Test
	void keepsTheSubtitlesWhenTheFailureHasNothingToDoWithThem() {
		Assertions.assertThat(policy.shouldRetryWithoutSubtitles("Could not find tag for codec pcm_s16le")).isFalse();
		Assertions.assertThat(policy.shouldRetryWithoutSubtitles("No space left on device")).isFalse();
	}

	@Test
	void treatsAMissingErrorOutputAsNoReasonToRetry() {
		Assertions.assertThat(policy.shouldRetryWithAac(AudioHandling.AUTO, null)).isFalse();
		Assertions.assertThat(policy.shouldRetryWithAac(AudioHandling.AUTO, "  ")).isFalse();
		Assertions.assertThat(policy.shouldRetryWithoutSubtitles(null)).isFalse();
		Assertions.assertThat(policy.shouldRetryWithoutSubtitles("")).isFalse();
	}

	@Test
	void encodesAacUpFrontOnlyForTheAlwaysAacOption() {
		Assertions.assertThat(policy.encodesAacUpFront(AudioHandling.AAC)).isTrue();

		Assertions.assertThat(policy.encodesAacUpFront(AudioHandling.AUTO)).isFalse();
		Assertions.assertThat(policy.encodesAacUpFront(AudioHandling.COPY)).isFalse();
	}
}