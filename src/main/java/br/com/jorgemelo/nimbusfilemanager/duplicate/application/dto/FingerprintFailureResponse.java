package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

/**
 * One file without a visual fingerprint, ready to render: the reason arrives
 * worded, explained and coloured, so the browser never turns a code into a
 * sentence or into a palette.
 *
 * @param reason technical code, for comparing and grouping - never displayed
 * @param reasonLabel what the user reads
 * @param reasonHint the same reason spelled out with examples, for the tooltip
 * @param tone which badge colour the reason wears, so the list can be read at a
 * glance without anyone mapping codes to colours in the browser
 * @param error the decoder's message, kept for whoever wants the detail
 */
public record FingerprintFailureResponse(String path, String reason, String reasonLabel, String reasonHint, String tone,
		String error) {
}