package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProcessingPropertiesTest {

	@Test
	void usesDocumentedDefaultsWhenUnset() {
		ProcessingProperties properties = new ProcessingProperties(null, null, null, null, null, null);

		assertThat(properties.workersOrDefault()).isEqualTo(4);
		assertThat(properties.queueCapacityOrDefault()).isEqualTo(ProcessingProperties.DEFAULT_QUEUE_CAPACITY);
		assertThat(properties.ffmpegPhotoHashLimitOrDefault()).isEqualTo(4);
		assertThat(properties.ffmpegVideoFrameLimitOrDefault())
				.isEqualTo(ProcessingProperties.DEFAULT_FFMPEG_VIDEO_FRAME_LIMIT);
		assertThat(properties.ffprobeVideoLimitOrDefault()).isEqualTo(ProcessingProperties.DEFAULT_FFPROBE_VIDEO_LIMIT);
		assertThat(properties.ffmpegTranscodeLimitOrDefault())
				.isEqualTo(ProcessingProperties.DEFAULT_FFMPEG_TRANSCODE_LIMIT);
	}

	@Test
	void clampsValuesBelowTheMinimum() {
		ProcessingProperties properties = new ProcessingProperties(0, 0, 0, 0, 0, 0);

		assertThat(properties.workersOrDefault()).isEqualTo(ProcessingProperties.MIN_WORKERS);
		assertThat(properties.queueCapacityOrDefault()).isEqualTo(ProcessingProperties.MIN_QUEUE_CAPACITY);
		assertThat(properties.ffmpegPhotoHashLimitOrDefault()).isEqualTo(ProcessingProperties.MIN_EXTERNAL_LIMIT);
		assertThat(properties.ffmpegVideoFrameLimitOrDefault()).isEqualTo(ProcessingProperties.MIN_EXTERNAL_LIMIT);
		assertThat(properties.ffprobeVideoLimitOrDefault()).isEqualTo(ProcessingProperties.MIN_EXTERNAL_LIMIT);
		assertThat(properties.ffmpegTranscodeLimitOrDefault()).isEqualTo(ProcessingProperties.MIN_EXTERNAL_LIMIT);
	}

	@Test
	void clampsValuesAboveTheMaximum() {
		ProcessingProperties properties = new ProcessingProperties(10_000, 10_000_000, 999, 999, 999, 999);

		assertThat(properties.workersOrDefault()).isEqualTo(ProcessingProperties.MAX_WORKERS);
		assertThat(properties.queueCapacityOrDefault()).isEqualTo(ProcessingProperties.MAX_QUEUE_CAPACITY);
		assertThat(properties.ffmpegPhotoHashLimitOrDefault()).isEqualTo(ProcessingProperties.MAX_EXTERNAL_LIMIT);
		assertThat(properties.ffmpegVideoFrameLimitOrDefault()).isEqualTo(ProcessingProperties.MAX_EXTERNAL_LIMIT);
		assertThat(properties.ffprobeVideoLimitOrDefault()).isEqualTo(ProcessingProperties.MAX_EXTERNAL_LIMIT);
		assertThat(properties.ffmpegTranscodeLimitOrDefault()).isEqualTo(ProcessingProperties.MAX_EXTERNAL_LIMIT);
	}
}