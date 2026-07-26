package br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums;

/**
 * What to do with the source audio while the video is re-encoded to H.265.
 */
public enum AudioHandling {

	/** Keep the original audio untouched ({@code -c:a copy}). */
	COPY,

	/** Always re-encode the audio to AAC. */
	AAC,

	/**
	 * The recommended option: try to keep the original audio and, only if ffmpeg
	 * refuses it (an audio codec the target container cannot hold), re-encode that
	 * one file to AAC and record the fallback in the execution history.
	 */
	AUTO
}