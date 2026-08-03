package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionFailure;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.MediaInfoService;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoMetadata;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.enums.MediaOrientation;

class ConvertedVideoValidatorTest {

	private final MediaInfoService mediaInfoService = mock(MediaInfoService.class);
	private final ConvertedVideoValidator validator = new ConvertedVideoValidator(mediaInfoService);

	@Test
	void acceptsAnHevcFileOfTheSameLength(@TempDir Path folder) throws Exception {
		Path converted = Files.writeString(folder.resolve("clip.mp4"), "data");

		when(mediaInfoService.extract(converted)).thenReturn(metadata("hevc", 120.0));

		Assertions.assertThat(validator.validate(converted, 120.0)).isEmpty();
	}

	@Test
	void acceptsTheRoundingDriftAContainerRewriteIntroduces(@TempDir Path folder) throws Exception {
		Path converted = Files.writeString(folder.resolve("clip.mp4"), "data");

		when(mediaInfoService.extract(converted)).thenReturn(metadata("HEVC", 120.4));

		Assertions.assertThat(validator.validate(converted, 120.0)).isEmpty();
	}

	@Test
	void rejectsAFileTheEncoderNeverWrote(@TempDir Path folder) {
		Assertions.assertThat(validator.validate(folder.resolve("missing.mp4"), 120.0))
				.contains(ConversionFailure.OUTPUT_MISSING);
	}

	@Test
	void rejectsAnEmptyFile(@TempDir Path folder) throws Exception {
		Path converted = Files.createFile(folder.resolve("clip.mp4"));

		Assertions.assertThat(validator.validate(converted, 120.0)).contains(ConversionFailure.OUTPUT_MISSING);
	}

	@Test
	void rejectsAFileThatIsNotHevcEvenThoughFfmpegReportedSuccess(@TempDir Path folder) throws Exception {
		Path converted = Files.writeString(folder.resolve("clip.mp4"), "data");

		when(mediaInfoService.extract(converted)).thenReturn(metadata("h264", 120.0));

		Assertions.assertThat(validator.validate(converted, 120.0)).contains(ConversionFailure.NOT_HEVC);
	}

	@Test
	void rejectsATruncatedFile(@TempDir Path folder) throws Exception {
		Path converted = Files.writeString(folder.resolve("clip.mp4"), "data");

		when(mediaInfoService.extract(converted)).thenReturn(metadata("hevc", 40.0));

		Assertions.assertThat(validator.validate(converted, 120.0)).contains(ConversionFailure.DURATION_MISMATCH);
	}

	@Test
	void rejectsAFileWhoseDurationCannotBeRead(@TempDir Path folder) throws Exception {
		Path converted = Files.writeString(folder.resolve("clip.mp4"), "data");

		when(mediaInfoService.extract(converted)).thenReturn(metadata("hevc", null));

		Assertions.assertThat(validator.validate(converted, 120.0)).contains(ConversionFailure.DURATION_MISMATCH);
	}

	@Test
	void skipsTheLengthCheckWhenTheSourceDurationIsUnknown(@TempDir Path folder) throws Exception {
		Path converted = Files.writeString(folder.resolve("clip.mp4"), "data");

		when(mediaInfoService.extract(converted)).thenReturn(metadata("hevc", 12.0));

		Assertions.assertThat(validator.validate(converted, null)).isEmpty();
		Assertions.assertThat(validator.validate(converted, 0.0)).isEmpty();
	}

	@Test
	void rejectsAFileFfprobeCannotRead(@TempDir Path folder) throws Exception {
		Path converted = Files.writeString(folder.resolve("clip.mp4"), "data");

		when(mediaInfoService.extract(any())).thenThrow(new IllegalStateException("ffprobe failed"));

		Assertions.assertThat(validator.validate(converted, 120.0)).contains(ConversionFailure.NOT_PROBEABLE);
	}

	@Test
	void rejectsAFileWithoutAVideoCodecAtAll(@TempDir Path folder) throws Exception {
		Path converted = Files.writeString(folder.resolve("clip.mp4"), "data");

		when(mediaInfoService.extract(converted)).thenReturn(metadata(null, 120.0));

		Assertions.assertThat(validator.validate(converted, 120.0)).contains(ConversionFailure.NOT_HEVC);
	}

	private VideoMetadata metadata(String videoCodec, Double durationSeconds) {
		return new VideoMetadata("mp4", videoCodec, "aac", null, null, null, null, null, null, durationSeconds, null,
				false, null, null, null, null, null, null, null, null, null, null, null, null,
				MediaOrientation.UNKNOWN);
	}
}