package br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums;

/**
 * Why a single file could not be converted (or could not be kept). The reason
 * is a code, not a sentence, so the wording lives in the message bundles and
 * the screen never translates a domain concept itself.
 */
public enum ConversionFailure {

	/** ffmpeg exited with an error, or could not be started at all. */
	ENCODER_FAILED,

	/** ffmpeg reported success but wrote nothing usable. */
	OUTPUT_MISSING,

	/** The converted file is not H.265 - the encode silently fell back. */
	NOT_HEVC,

	/** The converted file is shorter or longer than the source. */
	DURATION_MISMATCH,

	/** ffprobe could not read the converted file, so it cannot be trusted. */
	NOT_PROBEABLE,

	/** The validated file could not be moved into the library folder. */
	PLACEMENT_FAILED,

	/** The converted file is on disk but the catalog write failed. */
	CATALOG_FAILED,

	/** The original could not be moved into the quarantine. */
	QUARANTINE_FAILED,

	/** The user stopped the batch while this file was being encoded. */
	CANCELLED
}