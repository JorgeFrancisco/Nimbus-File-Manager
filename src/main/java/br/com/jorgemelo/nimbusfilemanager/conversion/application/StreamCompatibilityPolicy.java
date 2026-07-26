package br.com.jorgemelo.nimbusfilemanager.conversion.application;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.AudioHandling;

/**
 * Decides whether a failed attempt deserves another one with fewer demands on
 * the MP4 container, and which demand to drop.
 *
 * <p>
 * Both retries exist because the target container is fixed: MP4 cannot hold
 * every audio codec a source may carry, and it has no place at all for
 * image-based subtitles (PGS, VobSub). Those two are the only failures another
 * attempt can actually fix, so the markers matter - a retry is a full second
 * encode of the same file, and retrying on <em>any</em> failure would burn the
 * user's time twice over problems (a corrupt source, a full disk, a video the
 * encoder rejected) that no stream decision can solve.
 */
@Component
public class StreamCompatibilityPolicy {

	/**
	 * Lowercase fragments of what ffmpeg prints when the muxer refuses a copied
	 * stream: a codec MP4 has no tag for, an explicitly unsupported combination, or
	 * the header write that fails as a consequence.
	 */
	private static final List<String> INCOMPATIBLE_STREAM_MARKERS = List.of("could not find tag for codec",
			"codec not currently supported in container", "unsupported audio codec", "invalid audio stream",
			"no wav codec tag found for codec", "could not write header");

	/**
	 * Subtitle codecs (and wording) that name the subtitle track as the problem.
	 * MP4 only defines {@code mov_text}, so an image-based track can never be
	 * converted into it and the only way forward is to leave it behind.
	 */
	private static final List<String> SUBTITLE_MARKERS = List.of("subtitle", "hdmv_pgs", "dvd_sub", "dvb_sub",
			"vobsub", "mov_text");

	public boolean shouldRetryWithAac(AudioHandling handling, String ffmpegError) {
		return handling == AudioHandling.AUTO && !mentionsSubtitles(ffmpegError)
				&& matchesAny(ffmpegError, INCOMPATIBLE_STREAM_MARKERS);
	}

	/**
	 * True when the error blames a subtitle track MP4 cannot hold. Dropping it
	 * costs the subtitles of that one file, which is reported, and is the only way
	 * to still deliver the conversion.
	 */
	public boolean shouldRetryWithoutSubtitles(String ffmpegError) {
		return mentionsSubtitles(ffmpegError) && matchesAny(ffmpegError, INCOMPATIBLE_STREAM_MARKERS);
	}

	/** True when the very first attempt must already encode the audio to AAC. */
	public boolean encodesAacUpFront(AudioHandling handling) {
		return handling == AudioHandling.AAC;
	}

	private boolean mentionsSubtitles(String ffmpegError) {
		return matchesAny(ffmpegError, SUBTITLE_MARKERS);
	}

	private boolean matchesAny(String ffmpegError, List<String> markers) {
		if (ffmpegError == null || ffmpegError.isBlank()) {
			return false;
		}

		String normalized = ffmpegError.toLowerCase(Locale.ROOT);

		return markers.stream().anyMatch(normalized::contains);
	}
}