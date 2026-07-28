package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums;

/**
 * Why a file has no visual fingerprint. The reason drives what the screen says
 * and whether a retry is worth anything: only {@link #UNKNOWN} can change
 * outcome on its own, so everything else is terminal and stops burning
 * attempts.
 *
 * <p>
 * Recorded because "failed to compute" is not one problem. A photo whose bytes
 * are all zero is data the user lost and may still have in a backup; an
 * animated sticker is working software refusing a format it never supported.
 * Reporting both as the same ffmpeg error buried the first inside the second.
 */
public enum FingerprintFailureReason {

	/** The file holds no data at all - every byte is zero. */
	CORRUPTED_FILE(true),

	/** The content is not the media its extension claims (a saved web page). */
	NOT_AN_IMAGE(true),

	/** Real media in a variant the decoder does not read (extended WebP). */
	UNSUPPORTED_FORMAT(true),

	/** Real media whose stream the decoder rejects (a vendor trailer, a cut file). */
	DECODER_REFUSED(true),

	/** Unclassified: the only reason a retry may still resolve. */
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
}
