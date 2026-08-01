package br.com.jorgemelo.nimbusfilemanager.conversion.application.dto;

import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionOutcome;

/**
 * Per-file line of the conversion report shown when the batch ends. Sizes are
 * in bytes with a ready-to-render label, the outcome is a code (never a
 * translated string the screen would have to interpret), and {@code message}
 * carries the already localized explanation.
 */
public record ConversionFileResult(UUID mediaId, String fileName, ConversionOutcome outcome, long originalBytes,
		long convertedBytes, long savedBytes, String savedLabel, long elapsedMillis, ConversionAdjustments adjustments,
		boolean originalQuarantined, String message) {
}