package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import java.util.OptionalLong;

import org.springframework.stereotype.Component;

/**
 * Reads the {@code key=value} lines ffmpeg writes when started with
 * {@code -progress}, which is the only reliable way to follow a long encode:
 * the human-readable status line it prints by default is repainted with
 * carriage returns and is not meant to be parsed.
 *
 * <p>
 * Both {@code out_time_us} and {@code out_time_ms} are accepted and both are
 * read as <b>microseconds</b>. That is not a typo: {@code out_time_ms} has
 * always carried microseconds in ffmpeg's progress output, a long-standing
 * naming bug kept for backwards compatibility, and older builds emit only that
 * key. Values are also {@code N/A} until the first frame is written, so an
 * unparsable value simply means "no progress yet".
 */
@Component
public class FfmpegProgressParser {

	private static final String MICROSECONDS_KEY = "out_time_us=";
	private static final String LEGACY_MICROSECONDS_KEY = "out_time_ms=";

	/** How far into the source ffmpeg has written, or empty for any other line. */
	public OptionalLong elapsedMicroseconds(String line) {
		if (line == null) {
			return OptionalLong.empty();
		}

		String trimmed = line.trim();

		String value = valueOf(trimmed, MICROSECONDS_KEY);

		if (value == null) {
			value = valueOf(trimmed, LEGACY_MICROSECONDS_KEY);
		}

		if (value == null) {
			return OptionalLong.empty();
		}

		try {
			long microseconds = Long.parseLong(value);

			return microseconds < 0 ? OptionalLong.empty() : OptionalLong.of(microseconds);
		} catch (NumberFormatException _) {
			return OptionalLong.empty();
		}
	}

	/**
	 * Progress of the current file, clamped to 0-100. A source of unknown or zero
	 * duration reports 0 rather than a made-up number.
	 */
	public int percent(long elapsedMicroseconds, Double durationSeconds) {
		if (durationSeconds == null || durationSeconds <= 0) {
			return 0;
		}

		double ratio = elapsedMicroseconds / (durationSeconds * 1_000_000.0);

		return Math.clamp(Math.round(ratio * 100), 0, 100);
	}

	private String valueOf(String line, String key) {
		return line.startsWith(key) ? line.substring(key.length()).trim() : null;
	}
}