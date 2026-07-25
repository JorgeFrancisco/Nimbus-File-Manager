package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto.Coordinates;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.FfprobeResult;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.GeoLocation;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.VideoMetadata;
import br.com.jorgemelo.nimbusfilemanager.metadata.infrastructure.FfprobeProcessRunner;
import br.com.jorgemelo.nimbusfilemanager.processing.application.ExternalToolGate;
import br.com.jorgemelo.nimbusfilemanager.processing.domain.enums.ExternalToolCategory;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaOrientation;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class MediaInfoService {

	private static final int MAX_ISO6709_LENGTH = 64;
	private static final Pattern ISO6709_PATTERN = Pattern
			.compile("^([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)(?:[+-]\\d+(?:\\.\\d+)?)?$");

	private final NimbusFileManagerProperties properties;
	private final ObjectMapper objectMapper;
	private final FfprobeRunner ffprobeRunner;
	private final MediaOrientationResolver mediaOrientationResolver;
	private final AppSettingService appSettingService;

	@Autowired
	public MediaInfoService(NimbusFileManagerProperties properties, ObjectMapper objectMapper,
			AppSettingService appSettingService, ExternalToolGate externalToolGate,
			FfprobeProcessRunner processRunner) {
		// The production runner routes every ffprobe invocation through the gate, so
		// its
		// concurrency limit is enforced at the single point where the process is
		// spawned.
		this(properties, objectMapper, (ffprobePath, file) -> externalToolGate.run(ExternalToolCategory.FFPROBE_VIDEO,
				() -> processRunner.run(ffprobePath, file)), new MediaOrientationResolver(), appSettingService);
	}

	/**
	 * Test seam: lets unit tests inject a fake {@link FfprobeRunner} instead of
	 * spawning the real ffprobe process. {@code appSettingService} is still
	 * required (not defaulted to {@code null}) so {@link #ffprobePath()} never
	 * needs a null-check that production code could never actually hit.
	 */
	MediaInfoService(NimbusFileManagerProperties properties, ObjectMapper objectMapper, FfprobeRunner ffprobeRunner,
			AppSettingService appSettingService) {
		this(properties, objectMapper, ffprobeRunner, new MediaOrientationResolver(), appSettingService);
	}

	MediaInfoService(NimbusFileManagerProperties properties, ObjectMapper objectMapper, FfprobeRunner ffprobeRunner,
			MediaOrientationResolver mediaOrientationResolver, AppSettingService appSettingService) {
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.ffprobeRunner = ffprobeRunner;
		this.mediaOrientationResolver = mediaOrientationResolver;
		this.appSettingService = appSettingService;
	}

	/**
	 * Extracts video metadata via ffprobe.
	 *
	 * <p>
	 * A timeout or a non-zero exit code means ffprobe genuinely could not process
	 * the file (or, as with a missing dependency DLL on Windows, never even
	 * started), so those are thrown instead of silently swallowed: callers
	 * (inventory scan, metadata rebuild) already catch and count failures per file,
	 * and a video that could not be probed should show up as an error instead of
	 * being cataloged with empty video metadata. A failure to parse ffprobe's own
	 * output afterwards (a successful run producing unexpected JSON) is treated
	 * more defensively and still falls back to empty metadata, since that is more
	 * likely an unexpected bug in this method than a real problem with the file.
	 */
	public VideoMetadata extract(Path file) {
		FfprobeResult result;

		try {
			result = ffprobeRunner.run(ffprobePath(), file);
		} catch (Exception e) {
			throw new IllegalStateException("Could not run ffprobe for file: " + file + ". " + e.getMessage(), e);
		}

		if (!result.finished()) {
			throw new IllegalStateException("ffprobe timed out for file: " + file);
		}

		if (result.exitCode() != 0) {
			throw new IllegalStateException("ffprobe failed for file: " + file + ". Exit code: " + result.exitCode()
					+ describeExitCode(result.exitCode())
					+ (result.errorOutput() == null || result.errorOutput().isBlank() ? ""
							: ". Error: " + result.errorOutput()));
		}

		String json = result.output();

		try {
			JsonNode root = objectMapper.readTree(json);

			JsonNode format = root.path("format");
			JsonNode videoStream = findStream(root, "video");
			JsonNode audioStream = findStream(root, "audio");

			GeoLocation geoLocation = geoLocation(format, videoStream);

			Integer width = integer(videoStream, "width");
			Integer height = integer(videoStream, "height");
			Integer rotation = rotation(videoStream);

			String pixelFormat = text(videoStream, "pix_fmt");
			String colorSpace = text(videoStream, "color_space");
			String colorTransfer = text(videoStream, "color_transfer");
			String colorPrimaries = text(videoStream, "color_primaries");
			Integer bitDepth = integer(videoStream, "bits_per_raw_sample");

			Integer audioSampleRate = integer(audioStream, "sample_rate");
			Integer audioChannels = integer(audioStream, "channels");
			String audioChannelLayout = text(audioStream, "channel_layout");

			return new VideoMetadata(text(format, "format_name"), text(videoStream, "codec_name"),
					text(audioStream, "codec_name"), text(videoStream, "profile"), width, height, fps(videoStream),
					longValue(videoStream, "bit_rate"), longValue(format, "bit_rate"), doubleValue(format, "duration"),
					rotation, isHdr(videoStream), pixelFormat, colorSpace, colorTransfer, colorPrimaries, bitDepth,
					audioSampleRate, audioChannels, audioChannelLayout, captureDate(format, videoStream),
					geoLocation.latitude(), geoLocation.longitude(), json,
					mediaOrientationResolver.orientationType(width, height, rotation));
		} catch (Exception e) {
			log.warn("Could not parse ffprobe output for file: {}. Output: {}", file, json, e);

			return empty(json);
		}
	}

	/**
	 * Translates well-known Windows NTSTATUS process-launch failure codes into a
	 * human-readable hint. These show up as ffprobe's exit code when the OS itself
	 * refused to start the process (most commonly because a dependency DLL is
	 * missing next to ffprobe.exe), in which case ffprobe never runs far enough to
	 * write anything to stdout/stderr explaining why.
	 */
	private static String describeExitCode(int exitCode) {
		String description = switch (exitCode) {
		case 0xC0000135 -> "DLL not found: a dependency DLL is missing next to ffprobe.exe";
		case 0xC0000139 -> "Entry point not found: a dependency DLL is the wrong version";
		case 0xC0000142 -> "DLL initialization failed";
		case 0xC000007B -> "Invalid image format: likely a 32/64-bit architecture mismatch";
		case 0xC0000005 -> "Access violation while starting ffprobe";
		default -> null;
		};

		return description == null ? "" : " (" + description + ")";
	}

	private GeoLocation geoLocation(JsonNode format, JsonNode videoStream) {
		String value = text(format == null ? null : format.path("tags"), "location");

		if (value == null) {
			value = text(format == null ? null : format.path("tags"), "location-eng");
		}

		if (value == null) {
			value = text(format == null ? null : format.path("tags"), "com.apple.quicktime.location.ISO6709");
		}

		if (value == null) {
			value = text(videoStream == null ? null : videoStream.path("tags"), "location");
		}

		if (value == null) {
			return new GeoLocation(null, null);
		}

		return parseIso6709Location(value);
	}

	private GeoLocation parseIso6709Location(String value) {
		if (value == null || value.isBlank() || value.length() > MAX_ISO6709_LENGTH) {
			return new GeoLocation(null, null);
		}

		String normalized = value.trim();

		if (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}

		Matcher matcher = ISO6709_PATTERN.matcher(normalized);

		if (!matcher.matches()) {
			return new GeoLocation(null, null);
		}

		try {
			Double latitude = Double.valueOf(matcher.group(1));
			Double longitude = Double.valueOf(matcher.group(2));

			Coordinates coordinates = Coordinates.of(latitude, longitude);

			if (coordinates == null) {
				return new GeoLocation(null, null);
			}

			return new GeoLocation(coordinates.latitude(), coordinates.longitude());
		} catch (NumberFormatException _) {
			return new GeoLocation(null, null);
		}
	}

	private JsonNode findStream(JsonNode root, String codecType) {
		JsonNode streams = root.path("streams");

		if (!streams.isArray()) {
			return null;
		}

		for (JsonNode stream : streams) {
			if (codecType.equals(text(stream, "codec_type"))) {
				return stream;
			}
		}

		return null;
	}

	private String text(JsonNode node, String field) {
		if (node == null || node.isMissingNode() || node.path(field).isMissingNode() || node.path(field).isNull()) {
			return null;
		}

		String value = node.path(field).asText();

		return value == null || value.isBlank() ? null : value;
	}

	private Integer integer(JsonNode node, String field) {
		String value = text(node, field);

		if (value == null) {
			return null;
		}

		try {
			return Integer.valueOf(value);
		} catch (Exception _) {
			return null;
		}
	}

	private Long longValue(JsonNode node, String field) {
		String value = text(node, field);

		if (value == null) {
			return null;
		}

		try {
			return Long.valueOf(value);
		} catch (Exception _) {
			return null;
		}
	}

	private Double doubleValue(JsonNode node, String field) {
		String value = text(node, field);

		if (value == null) {
			return null;
		}

		try {
			return Double.valueOf(value);
		} catch (Exception _) {
			return null;
		}
	}

	private Double fps(JsonNode videoStream) {
		String value = text(videoStream, "avg_frame_rate");

		if (value == null || "0/0".equals(value)) {
			value = text(videoStream, "r_frame_rate");
		}

		if (value == null || "0/0".equals(value)) {
			return null;
		}

		try {
			if (value.contains("/")) {
				String[] parts = value.split("/");

				double numerator = Double.parseDouble(parts[0]);
				double denominator = Double.parseDouble(parts[1]);

				if (denominator == 0) {
					return null;
				}

				return numerator / denominator;
			}

			return Double.valueOf(value);
		} catch (Exception _) {
			return null;
		}
	}

	private Integer rotation(JsonNode videoStream) {
		if (videoStream == null || videoStream.isMissingNode() || videoStream.isNull()) {
			return null;
		}

		Integer rotate = integer(videoStream.path("tags"), "rotate");

		if (rotate != null) {
			return rotate;
		}

		JsonNode sideDataList = videoStream.path("side_data_list");

		if (!sideDataList.isArray()) {
			return null;
		}

		for (JsonNode sideData : sideDataList) {
			Integer rotation = integer(sideData, "rotation");

			if (rotation != null) {
				return rotation;
			}
		}

		return null;
	}

	private Boolean isHdr(JsonNode videoStream) {
		if (videoStream == null || videoStream.isMissingNode() || videoStream.isNull()) {
			return false;
		}

		String colorTransfer = text(videoStream, "color_transfer");
		String colorPrimaries = text(videoStream, "color_primaries");
		String colorSpace = text(videoStream, "color_space");

		return contains(colorTransfer, "smpte2084") || contains(colorTransfer, "arib-std-b67")
				|| contains(colorPrimaries, "bt2020") || contains(colorSpace, "bt2020");
	}

	private boolean contains(String value, String expected) {
		return value != null && value.toLowerCase().contains(expected.toLowerCase());
	}

	private LocalDateTime captureDate(JsonNode format, JsonNode videoStream) {
		String value = text(format == null ? null : format.path("tags"), "creation_time");

		if (value == null) {
			value = text(videoStream == null ? null : videoStream.path("tags"), "creation_time");
		}

		if (value == null) {
			return null;
		}

		try {
			// ffprobe/MediaInfo returns creation_time in UTC (e.g. 2020-11-14T00:20:27Z).
			// We convert it to the local time zone before persisting, to keep it consistent
			// with EXIF, the file name and date-based organization.
			return OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
		} catch (Exception _) {
			return null;
		}
	}

	private VideoMetadata empty(String json) {
		return new VideoMetadata(null, null, null, null, null, null, null, null, null, null, null, false, null, null,
				null, null, null, null, null, null, null, null, null, json, MediaOrientation.UNKNOWN);
	}

	private String ffprobePath() {
		return appSettingService.stringValue(SettingsConstants.TOOL_FFPROBE, properties.tools().ffprobe());
	}
}