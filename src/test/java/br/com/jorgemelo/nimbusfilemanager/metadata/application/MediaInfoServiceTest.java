package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.FfprobeResult;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.GeoLocation;
import br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.FfprobeProcessRunner;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ExternalToolGate;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ProcessingMetrics;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolPaths;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaOrientation;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.ProcessingProperties;

class MediaInfoServiceTest {

	@TempDir
	Path tempDir;

	@Test
	void parseIso6709ShouldAcceptValidCoordinatesAltitudeAndTrailingSlash() throws Exception {
		Assertions.assertThat(parseLocation("+12.3456-045.6789")).isEqualTo(new GeoLocation(12.3456, -45.6789));
		Assertions.assertThat(parseLocation("+12.3456-045.6789+100.0")).isEqualTo(new GeoLocation(12.3456, -45.6789));
		Assertions.assertThat(parseLocation("-23.5505+046.6333/")).isEqualTo(new GeoLocation(-23.5505, 46.6333));
	}

	@Test
	void parseIso6709ShouldRejectInvalidBoundsEmptyInvalidAndExcessivelyLongValues() throws Exception {
		Assertions.assertThat(parseLocation("+90.0000-180.0000").latitude()).isEqualTo(90.0);
		Assertions.assertThat(parseLocation("+90.0001-180.0001").latitude()).isNull();
		Assertions.assertThat(parseLocation("+1.0-180.0001").longitude()).isNull();
		Assertions.assertThat(parseLocation(null).latitude()).isNull();
		Assertions.assertThat(parseLocation(" ").latitude()).isNull();
		Assertions.assertThat(parseLocation("invalid").latitude()).isNull();
		Assertions.assertThat(parseLocation("+" + "1".repeat(100) + "-1").latitude()).isNull();
		Assertions.assertThat(parseLocation("+Infinity-1").latitude()).isNull();
	}

	@Test
	void parseIso6709ShouldTreatOnlyTheExactZeroPairAsMissingGps() throws Exception {
		Assertions.assertThat(parseLocation("+0.0+0.0")).isEqualTo(new GeoLocation(null, null));
		Assertions.assertThat(parseLocation("+0.0-45.0")).isEqualTo(new GeoLocation(0.0, -45.0));
		Assertions.assertThat(parseLocation("-23.0+0.0")).isEqualTo(new GeoLocation(-23.0, 0.0));
	}

	private GeoLocation parseLocation(String value) throws Exception {
		Method method = MediaInfoService.class.getDeclaredMethod("parseIso6709Location", String.class);
		method.setAccessible(true);

		return (GeoLocation) method.invoke(service(new FfprobeResult(true, 0, "")), value);
	}

	@Test
	void extractShouldMapVideoAudioLocationHdrRotationAndOrientation() {
		String json = """
				{
				  "format": {
				    "format_name": "mov,mp4",
				    "bit_rate": "9000",
				    "duration": "12.5",
				    "tags": {
				      "creation_time": "2024-05-09T13:30:00Z",
				      "location": "+12.3456-045.6789/"
				    }
				  },
				  "streams": [
				    {
				      "codec_type": "video",
				      "codec_name": "hevc",
				      "profile": "Main 10",
				      "width": 3840,
				      "height": 2160,
				      "avg_frame_rate": "30000/1001",
				      "bit_rate": "8000",
				      "pix_fmt": "yuv420p10le",
				      "color_space": "bt2020nc",
				      "color_transfer": "smpte2084",
				      "color_primaries": "bt2020",
				      "bits_per_raw_sample": "10",
				      "side_data_list": [{ "rotation": 90 }]
				    },
				    {
				      "codec_type": "audio",
				      "codec_name": "aac",
				      "sample_rate": "48000",
				      "channels": 2,
				      "channel_layout": "stereo"
				    }
				  ]
				}
				""";

		var metadata = service(new FfprobeResult(true, 0, json)).extract(Path.of("C:/video.mp4"));

		Assertions.assertThat(metadata.container()).isEqualTo("mov,mp4");
		Assertions.assertThat(metadata.videoCodec()).isEqualTo("hevc");
		Assertions.assertThat(metadata.audioCodec()).isEqualTo("aac");
		Assertions.assertThat(metadata.videoProfile()).isEqualTo("Main 10");
		Assertions.assertThat(metadata.width()).isEqualTo(3840);
		Assertions.assertThat(metadata.height()).isEqualTo(2160);
		Assertions.assertThat(metadata.fps()).isCloseTo(29.97, Assertions.offset(0.01));
		Assertions.assertThat(metadata.videoBitrate()).isEqualTo(8000);
		Assertions.assertThat(metadata.totalBitrate()).isEqualTo(9000);
		Assertions.assertThat(metadata.durationSeconds()).isEqualTo(12.5);
		Assertions.assertThat(metadata.rotation()).isEqualTo(90);
		Assertions.assertThat(metadata.hdr()).isTrue();
		Assertions.assertThat(metadata.bitDepth()).isEqualTo(10);
		Assertions.assertThat(metadata.audioSampleRate()).isEqualTo(48000);
		Assertions.assertThat(metadata.audioChannels()).isEqualTo(2);
		Assertions.assertThat(metadata.audioChannelLayout()).isEqualTo("stereo");
		Assertions.assertThat(metadata.latitude()).isEqualTo(12.3456);
		Assertions.assertThat(metadata.longitude()).isEqualTo(-45.6789);
		Assertions.assertThat(metadata.captureDate()).isNotNull();
		Assertions.assertThat(metadata.mediaInfoJson()).isEqualTo(json);
		Assertions.assertThat(metadata.orientationType()).isEqualTo(MediaOrientation.PORTRAIT);
	}

	@Test
	void extractShouldFallbackToRFrameRateRotateTagAndVideoLocation() {
		String json = """
				{
				  "format": { "format_name": "mp4", "tags": {} },
				  "streams": [
				    {
				      "codec_type": "video",
				      "codec_name": "h264",
				      "width": 100,
				      "height": 100,
				      "avg_frame_rate": "0/0",
				      "r_frame_rate": "25",
				      "tags": {
				        "rotate": "180",
				        "location": "+01.0000+002.0000"
				      }
				    }
				  ]
				}
				""";

		var metadata = service(new FfprobeResult(true, 0, json)).extract(Path.of("C:/video.mp4"));

		Assertions.assertThat(metadata.fps()).isEqualTo(25.0);
		Assertions.assertThat(metadata.rotation()).isEqualTo(180);
		Assertions.assertThat(metadata.latitude()).isEqualTo(1.0);
		Assertions.assertThat(metadata.longitude()).isEqualTo(2.0);
		Assertions.assertThat(metadata.orientationType()).isEqualTo(MediaOrientation.SQUARE);
	}

	@Test
	void extractShouldHandleAlternativeLocationsMissingStreamsAndOrientationBranches() {
		var locationEng = service(new FfprobeResult(true, 0, locationJson("location-eng")))
				.extract(Path.of("C:/video.mp4"));
		var quickTimeLocation = service(
				new FfprobeResult(true, 0, locationJson("com.apple.quicktime.location.ISO6709")))
				.extract(Path.of("C:/video.mp4"));
		var noStreams = service(new FfprobeResult(true, 0, """
				{ "format": { "tags": {} }, "streams": {} }
				""")).extract(Path.of("C:/video.mp4"));
		var landscape = service(new FfprobeResult(true, 0, dimensionsJson(200, 100))).extract(Path.of("C:/video.mp4"));
		var portrait = service(new FfprobeResult(true, 0, dimensionsJson(100, 200))).extract(Path.of("C:/video.mp4"));

		Assertions.assertThat(locationEng.latitude()).isEqualTo(10.0);
		Assertions.assertThat(quickTimeLocation.longitude()).isEqualTo(20.0);
		Assertions.assertThat(noStreams.orientationType()).isEqualTo(MediaOrientation.UNKNOWN);
		Assertions.assertThat(landscape.orientationType()).isEqualTo(MediaOrientation.LANDSCAPE);
		Assertions.assertThat(portrait.orientationType()).isEqualTo(MediaOrientation.PORTRAIT);
	}

	@Test
	void extractShouldReturnEmptyForInvalidJsonAndInvalidNumbers() {
		var invalidJson = service(new FfprobeResult(true, 0, "{")).extract(Path.of("C:/video.mp4"));
		var invalidValues = service(new FfprobeResult(true, 0, invalidValuesJson())).extract(Path.of("C:/video.mp4"));

		Assertions.assertThat(invalidJson.orientationType()).isEqualTo(MediaOrientation.UNKNOWN);
		Assertions.assertThat(invalidValues.width()).isNull();
		Assertions.assertThat(invalidValues.height()).isNull();
		Assertions.assertThat(invalidValues.fps()).isNull();
		Assertions.assertThat(invalidValues.videoBitrate()).isNull();
		Assertions.assertThat(invalidValues.totalBitrate()).isNull();
		Assertions.assertThat(invalidValues.durationSeconds()).isNull();
		Assertions.assertThat(invalidValues.rotation()).isNull();
		Assertions.assertThat(invalidValues.captureDate()).isNull();
		Assertions.assertThat(invalidValues.latitude()).isNull();
		Assertions.assertThat(invalidValues.longitude()).isNull();
		Assertions.assertThat(invalidValues.hdr()).isFalse();
	}

	@Test
	void extractShouldThrowOnTimeoutSoTheFileIsCountedAsAnError() {
		var service = service(new FfprobeResult(false, -1, "partial"));

		Path file = Path.of("C:/video.mp4");

		Assertions.assertThatThrownBy(() -> service.extract(file)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("timed out");
	}

	@Test
	void extractShouldThrowOnNonZeroExitCodeWithErrorOutputInMessage() {
		var service = service(new FfprobeResult(true, 1, "", "Unsupported codec"));

		Path file = Path.of("C:/video.mp4");

		Assertions.assertThatThrownBy(() -> service.extract(file)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Exit code: 1").hasMessageContaining("Unsupported codec");
	}

	@Test
	void extractShouldDescribeWellKnownWindowsDllNotFoundExitCode() {
		var service = service(new FfprobeResult(true, 0xC0000135, "", ""));

		Path file = Path.of("C:/video.mp4");

		Assertions.assertThatThrownBy(() -> service.extract(file)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("DLL not found");
	}

	/**
	 * A broken ffprobe install fails with an NTSTATUS code that means nothing to
	 * the user, so every code we can name gets its own hint in the message - that
	 * hint is the whole point of the mapping.
	 */
	@Test
	void extractShouldDescribeEveryKnownWindowsStartupFailureCode() {
		Path file = Path.of("C:/video.mp4");

		Assertions.assertThatThrownBy(() -> service(new FfprobeResult(true, 0xC0000139, "", "")).extract(file))
				.hasMessageContaining("Entry point not found");
		Assertions.assertThatThrownBy(() -> service(new FfprobeResult(true, 0xC0000142, "", "")).extract(file))
				.hasMessageContaining("DLL initialization failed");
		Assertions.assertThatThrownBy(() -> service(new FfprobeResult(true, 0xC000007B, "", "")).extract(file))
				.hasMessageContaining("32/64-bit architecture mismatch");
		Assertions.assertThatThrownBy(() -> service(new FfprobeResult(true, 0xC0000005, "", "")).extract(file))
				.hasMessageContaining("Access violation");
	}

	/**
	 * A failure to even launch the process is an error for the file, not a silent
	 * empty result - the original cause has to survive in the message.
	 */
	@Test
	void extractShouldWrapAFailureToRunFfprobe() {
		MediaInfoService service = new MediaInfoService(new ObjectMapper(), (_, _) -> {
			throw new IllegalStateException("ffprobe binary is missing");
		}, externalToolPaths("ffprobe"));

		Path file = Path.of("C:/video.mp4");

		Assertions.assertThatThrownBy(() -> service.extract(file)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Could not run ffprobe").hasMessageContaining("ffprobe binary is missing");
	}

	@Test
	void extractShouldThrowWithoutDllHintForUnrecognizedExitCode() {
		var service = service(new FfprobeResult(true, 42, "", ""));

		Path file = Path.of("C:/video.mp4");

		Assertions.assertThatThrownBy(() -> service.extract(file)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Exit code: 42").hasMessageNotContaining("DLL");
	}

	@Test
	void extractShouldKeepAbsentOptionalNumbersAsNull() {
		String json = """
				{
				  "format": { "format_name": "mp4", "tags": {} },
				  "streams": [
				    {
				      "codec_type": "video",
				      "codec_name": "h264",
				      "width": 1920,
				      "height": 1080,
				      "side_data_list": []
				    }
				  ]
				}
				""";

		var metadata = service(new FfprobeResult(true, 0, json)).extract(Path.of("C:/video.mp4"));

		Assertions.assertThat(metadata.videoBitrate()).isNull();
		Assertions.assertThat(metadata.totalBitrate()).isNull();
		Assertions.assertThat(metadata.durationSeconds()).isNull();
		Assertions.assertThat(metadata.fps()).isNull();
		Assertions.assertThat(metadata.rotation()).isNull();
		Assertions.assertThat(metadata.latitude()).isNull();
		Assertions.assertThat(metadata.longitude()).isNull();
		Assertions.assertThat(metadata.hdr()).isFalse();
		Assertions.assertThat(metadata.orientationType()).isEqualTo(MediaOrientation.LANDSCAPE);
	}

	@Test
	void extractShouldTreatRotationWithoutSideDataAsMissing() {
		String json = """
				{
				  "format": { "format_name": "mp4" },
				  "streams": [
				    {
				      "codec_type": "video",
				      "width": 1080,
				      "height": 1920
				    }
				  ]
				}
				""";

		var metadata = service(new FfprobeResult(true, 0, json)).extract(Path.of("C:/video.mp4"));

		Assertions.assertThat(metadata.rotation()).isNull();
		Assertions.assertThat(metadata.orientationType()).isEqualTo(MediaOrientation.PORTRAIT);
	}

	@Test
	void extractShouldUseRotationToCalculateEffectiveOrientation() {
		String json = """
				{
				  "format": { "format_name": "mp4" },
				  "streams": [
				    {
				      "codec_type": "video",
				      "width": 1920,
				      "height": 1080,
				      "tags": { "rotate": "90" }
				    }
				  ]
				}
				""";

		var metadata = service(new FfprobeResult(true, 0, json)).extract(Path.of("C:/video.mp4"));

		Assertions.assertThat(metadata.rotation()).isEqualTo(90);
		Assertions.assertThat(metadata.orientationType()).isEqualTo(MediaOrientation.PORTRAIT);
	}

	@Test
	void extractShouldNotMarkSdrBt709VideoAsHdr() {
		String json = """
				{
				  "format": { "format_name": "mp4" },
				  "streams": [
				    {
				      "codec_type": "video",
				      "width": 1920,
				      "height": 1080,
				      "color_transfer": "bt709",
				      "color_primaries": "bt709",
				      "color_space": "bt709"
				    }
				  ]
				}
				""";

		var metadata = service(new FfprobeResult(true, 0, json)).extract(Path.of("C:/video.mp4"));

		Assertions.assertThat(metadata.hdr()).isFalse();
		Assertions.assertThat(metadata.orientationType()).isEqualTo(MediaOrientation.LANDSCAPE);
	}

	@Test
	void extractShouldRunDefaultFfprobeCommand() throws Exception {
		Path video = Files.writeString(tempDir.resolve("video.mp4"), "video");

		String json = """
				{ "format": { "format_name": "mp4" }, "streams": [] }
				""".strip();

		Path ffprobe = writeFakeFfprobe(json);

		var metadata = new MediaInfoService(new ObjectMapper(), externalToolPaths(ffprobe.toString()), gate(),
				new FfprobeProcessRunner()).extract(video);

		Assertions.assertThat(metadata.container()).isEqualTo("mp4");
		Assertions.assertThat(metadata.mediaInfoJson()).contains("\"format_name\": \"mp4\"");
	}

	/**
	 * {@link MediaInfoService} execs {@code ffprobePath} directly (no shell
	 * wrapper), so the fake executable has to be something the current OS can
	 * actually run: a {@code .cmd} batch file on Windows, or a shebang shell script
	 * with the executable bit set on POSIX (Linux/macOS, e.g. CI) - a Windows batch
	 * file has neither an execute permission nor a recognizable format there.
	 */
	private Path writeFakeFfprobe(String json) throws Exception {
		if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
			Path script = tempDir.resolve("ffprobe.cmd");

			Files.writeString(script, "@echo off\r\necho " + json + "\r\n");

			return script;
		}

		Path script = tempDir.resolve("ffprobe.sh");

		Files.writeString(script, "#!/bin/sh\necho '" + json + "'\n");
		script.toFile().setExecutable(true);

		return script;
	}

	private MediaInfoService service(FfprobeResult result) {
		return new MediaInfoService(new ObjectMapper(), (_, _) -> result, externalToolPaths("ffprobe"));
	}

	/**
	 * A stub that resolves ffprobe exactly like production does when no admin
	 * override is stored: straight to the packaged path.
	 */
	private ExternalToolPaths externalToolPaths(String ffprobe) {
		ExternalToolPaths externalToolPaths = mock(ExternalToolPaths.class);

		when(externalToolPaths.ffprobe()).thenReturn(ffprobe);

		return externalToolPaths;
	}

	private ExternalToolGate gate() {
		return new ExternalToolGate(new ProcessingProperties(2, 8, 2, 2, 2, 1), new ProcessingMetrics());
	}

	private String invalidValuesJson() {
		return """
				{
				  "format": {
				    "duration": "bad",
				    "tags": {
				      "creation_time": "bad",
				      "location": "bad"
				    }
				  },
				  "streams": [
				    {
				      "codec_type": "video",
				      "width": "bad",
				      "height": "bad",
				      "avg_frame_rate": "1/0",
				      "bit_rate": "bad"
				    }
				  ]
				}
				""";
	}

	private String locationJson(String field) {
		return """
				{
				  "format": {
				    "tags": {
				      "%s": "+10.0000+020.0000/"
				    }
				  },
				  "streams": [
				    {
				      "codec_type": "video",
				      "width": 100,
				      "height": 100
				    }
				  ]
				}
				""".formatted(field);
	}

	private String dimensionsJson(int width, int height) {
		return """
				{
				  "format": {},
				  "streams": [
				    {
				      "codec_type": "video",
				      "width": %d,
				      "height": %d,
				      "color_transfer": "arib-std-b67"
				    }
				  ]
				}
				""".formatted(width, height);
	}
}