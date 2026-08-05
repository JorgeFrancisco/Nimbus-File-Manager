package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VideoSimilarityPropertiesTest {

	@Test
	void usesDocumentedDefaultsWhenUnset() {
		VideoSimilarityProperties properties = new VideoSimilarityProperties(null, null, null, null, null);

		assertThat(properties.minConcordantFramesOrDefault())
				.isEqualTo(VideoSimilarityProperties.DEFAULT_MIN_CONCORDANT_FRAMES);
		assertThat(properties.trimmedLowestFramesOrDefault())
				.isEqualTo(VideoSimilarityProperties.DEFAULT_TRIMMED_LOWEST_FRAMES);
		assertThat(properties.maxFrameHashDistanceOrDefault())
				.isEqualTo(VideoSimilarityProperties.DEFAULT_MAX_FRAME_HASH_DISTANCE);
		assertThat(properties.durationToleranceSecondsOrDefault())
				.isEqualTo(VideoSimilarityProperties.DEFAULT_DURATION_TOLERANCE_SECONDS);
		assertThat(properties.aspectRatioToleranceOrDefault())
				.isEqualTo(VideoSimilarityProperties.DEFAULT_ASPECT_RATIO_TOLERANCE);
	}

	@Test
	void clampsQuorumAndTrimToTheirBounds() {
		VideoSimilarityProperties tooLow = new VideoSimilarityProperties(0, -1, null, null, null);
		VideoSimilarityProperties tooHigh = new VideoSimilarityProperties(999, 999, null, null, null);

		assertThat(tooLow.minConcordantFramesOrDefault()).isEqualTo(1);
		assertThat(tooLow.trimmedLowestFramesOrDefault()).isZero();
		assertThat(tooHigh.minConcordantFramesOrDefault()).isEqualTo(VideoSimilarityProperties.MAX_FRAME_QUORUM);
		assertThat(tooHigh.trimmedLowestFramesOrDefault()).isEqualTo(VideoSimilarityProperties.MAX_FRAME_QUORUM);
	}

	@Test
	void clampsFrameHashDistanceToItsBounds() {
		VideoSimilarityProperties tooLow = new VideoSimilarityProperties(null, null, -5, null, null);
		VideoSimilarityProperties tooHigh = new VideoSimilarityProperties(null, null, 9999, null, null);

		assertThat(tooLow.maxFrameHashDistanceOrDefault()).isEqualTo(VideoSimilarityProperties.MIN_FRAME_HASH_DISTANCE);
		assertThat(tooHigh.maxFrameHashDistanceOrDefault())
				.isEqualTo(VideoSimilarityProperties.MAX_FRAME_HASH_DISTANCE);
	}

	@Test
	void negativeTolerancesFallBackToTheirDefaults() {
		VideoSimilarityProperties properties = new VideoSimilarityProperties(null, null, null, -1.0, -1.0);

		assertThat(properties.durationToleranceSecondsOrDefault())
				.isEqualTo(VideoSimilarityProperties.DEFAULT_DURATION_TOLERANCE_SECONDS);
		assertThat(properties.aspectRatioToleranceOrDefault())
				.isEqualTo(VideoSimilarityProperties.DEFAULT_ASPECT_RATIO_TOLERANCE);
	}
}