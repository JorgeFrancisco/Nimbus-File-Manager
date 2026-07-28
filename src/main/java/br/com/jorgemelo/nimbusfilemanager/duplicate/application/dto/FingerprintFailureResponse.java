package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

/**
 * One file without a visual fingerprint, ready to render: the reason already
 * says which of them it is, in the reader's language, and {@code severe} is the
 * server's call on what deserves attention rather than the screen's.
 *
 * @param reason technical code, for comparing and grouping - never displayed
 * @param reasonLabel what the user reads
 * @param severe the file's own data is gone; the others are files nothing can
 *        decode, which is a limitation, not a loss
 * @param error the decoder's message, kept for whoever wants the detail
 */
public record FingerprintFailureResponse(String path, String reason, String reasonLabel, boolean severe,
		String error) {
}