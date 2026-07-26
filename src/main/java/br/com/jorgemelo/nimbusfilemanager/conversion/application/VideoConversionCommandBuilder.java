package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.CommandOptions;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionQuality;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ExternalToolPaths;

/**
 * The single place an ffmpeg command line is assembled. Nothing else in the
 * application concatenates encoder arguments, so adding a hardware encoder
 * (NVENC, Quick Sync, AMF) or another codec (AV1) later is a change to this
 * class alone - the screen, the service and the process runner all stay
 * untouched because they only speak in {@link ConversionQuality} profiles.
 *
 * <p>
 * The target is always H.265 inside MP4. Because the container is fixed, the
 * streams are mapped by type instead of with a blanket {@code -map 0}: MP4 holds
 * video, audio, timed text and data, and mapping anything else (an MKV font
 * attachment, or cover art that {@code 0:v} would hand to the encoder as a
 * second video stream) either fails the mux or produces nonsense. Metadata and
 * chapters are carried over, subtitles are converted to MP4's own
 * {@code mov_text}, and only the video is re-encoded - not even that when the
 * source is already H.265 and merely needs a remux.
 *
 * <p>
 * {@code -progress pipe:1} makes ffmpeg report machine-readable progress on
 * stdout, which is what drives the per-file progress bar; {@code -nostats}
 * silences the human-readable variant so stderr stays useful for diagnosing a
 * failure.
 */
@Component
public class VideoConversionCommandBuilder {

	private final ExternalToolPaths externalToolPaths;

	public VideoConversionCommandBuilder(ExternalToolPaths externalToolPaths) {
		this.externalToolPaths = externalToolPaths;
	}

	public List<String> build(Path input, Path output, CommandOptions options) {
		List<String> command = new ArrayList<>();

		command.add(externalToolPaths.ffmpeg());
		command.add("-y");
		command.add("-hide_banner");
		command.add("-loglevel");
		command.add("error");
		command.add("-nostats");
		command.add("-progress");
		command.add("pipe:1");
		command.add("-i");
		command.add(input.toAbsolutePath().normalize().toString());

		addStreamSelection(command, options.includeSubtitles());
		addVideoEncoding(command, options);
		addAudioEncoding(command, options.encodeAudioAsAac());
		addSideStreams(command, options.includeSubtitles());

		command.add(output.toAbsolutePath().normalize().toString());

		return List.copyOf(command);
	}

	/**
	 * Every selector is optional ({@code ?}) so a file without audio, subtitles or
	 * data streams is not a failure.
	 */
	private void addStreamSelection(List<String> command, boolean includeSubtitles) {
		command.add("-map");
		command.add("0:V?");
		command.add("-map");
		command.add("0:a?");

		if (includeSubtitles) {
			command.add("-map");
			command.add("0:s?");
		}

		command.add("-map");
		command.add("0:d?");
		command.add("-map_metadata");
		command.add("0");
		command.add("-map_chapters");
		command.add("0");
	}

	private void addVideoEncoding(List<String> command, CommandOptions options) {
		command.add("-c:v");

		if (options.copyVideo()) {
			// Already H.265: re-encoding would cost hours and lose quality for nothing,
			// so the stream is remuxed into MP4 untouched.
			command.add("copy");
		} else {
			command.add("libx265");
			command.add("-crf");
			command.add(String.valueOf(options.quality().crf()));
			command.add("-preset");
			command.add(options.quality().preset());
		}

		// Without the hvc1 tag QuickTime, macOS, iOS and Windows Photos refuse to play
		// a perfectly valid H.265 MP4; use_metadata_tags keeps the non-standard MP4
		// tags (camera maker notes, capture location) that -map_metadata alone drops.
		command.add("-tag:v");
		command.add("hvc1");
		command.add("-movflags");
		command.add("use_metadata_tags");
	}

	private void addAudioEncoding(List<String> command, boolean encodeAudioAsAac) {
		command.add("-c:a");

		if (!encodeAudioAsAac) {
			command.add("copy");

			return;
		}

		command.add("aac");
		command.add("-b:a");
		command.add("192k");
	}

	/**
	 * Subtitles become {@code mov_text}, the only text format MP4 defines: a SubRip
	 * or ASS track from an MKV survives the move, an image-based one (PGS/VobSub)
	 * cannot and makes ffmpeg fail - which is what the subtitle-less retry exists
	 * for. Data streams (timecode, GoPro telemetry) are copied, and anything
	 * unknown is dropped instead of costing the whole conversion.
	 */
	private void addSideStreams(List<String> command, boolean includeSubtitles) {
		if (includeSubtitles) {
			command.add("-c:s");
			command.add("mov_text");
		}

		command.add("-c:d");
		command.add("copy");
		command.add("-ignore_unknown");

		// The output is written as a .tmp file, so the muxer cannot be inferred from
		// the extension and has to be stated.
		command.add("-f");
		command.add("mp4");
	}
}