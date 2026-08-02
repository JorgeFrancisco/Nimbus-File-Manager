package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums;

import java.util.Arrays;
import java.util.List;

/**
 * Why a file has no visual fingerprint. The reason drives what the screen says
 * and whether a retry is worth anything: a terminal one spends its attempts at
 * once, so the backlog stops picking the file up on every pass.
 *
 * <p>
 * Recorded because "failed to compute" is not one problem. A photo whose bytes
 * are all zero is data the user lost and may still have in a backup; an
 * animated sticker is working software refusing a format it never supported.
 * Reporting both as the same ffmpeg error buried the first inside the second.
 *
 * <p>
 * The distinction that matters most is not between formats, though: it is
 * whether the verdict is about the file at all. Everything here except
 * {@link #TOOL_UNAVAILABLE} and {@link #UNKNOWN} says something about the bytes
 * on disk, and saying that when the decoder never ran writes off a perfectly
 * good photo for a reason that has nothing to do with it.
 */
public enum FingerprintFailureReason {

	/** The file holds no data at all - every byte is zero. */
	CORRUPTED_FILE(true),

	/** The content is not the media its extension claims (a saved web page). */
	NOT_AN_IMAGE(true),

	/** Real media in a variant the decoder does not read (extended WebP). */
	UNSUPPORTED_FORMAT(true),

	/** Real media whose stream the decoder rejects (vendor trailer, cut file). */
	DECODER_REFUSED(true),

	/**
	 * The decoder never ran - the binary was missing, or the saved path pointed
	 * somewhere it no longer is. Nothing here is about the file, so the verdict
	 * lasts exactly as long as the installation problem does.
	 */
	TOOL_UNAVAILABLE(false),

	/** Unclassified: whatever went wrong, a retry may still resolve it. */
	UNKNOWN(false);

	private final boolean terminal;

	FingerprintFailureReason(boolean terminal) {
		this.terminal = terminal;
	}

	/**
	 * Whether trying again is pointless. A terminal reason spends its attempts at
	 * once, so the backlog stops picking the file up on every pass.
	 */
	public boolean terminal() {
		return terminal;
	}

	/**
	 * What the manual retry is allowed to clear. Derived from {@link #terminal()}
	 * rather than listed, so a reason added later is covered by the button the day
	 * it exists - the previous list named {@link #UNKNOWN} alone, and a run whose
	 * ffmpeg could not start left files no button could return to the queue.
	 */
	public static List<FingerprintFailureReason> retryable() {
		return Arrays.stream(values()).filter(reason -> !reason.terminal).toList();
	}
}