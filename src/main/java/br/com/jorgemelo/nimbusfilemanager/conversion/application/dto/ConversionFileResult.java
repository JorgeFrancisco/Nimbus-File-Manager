package br.com.jorgemelo.nimbusfilemanager.conversion.application.dto;

import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionOutcome;

/**
 * Per-file line of the conversion report shown when the batch ends. Sizes are
 * in bytes with a ready-to-render label, the outcome is a code (never a
 * translated string the screen would have to interpret), and {@code message}
 * carries the already localized explanation.
 *
 * <p>
 * {@code revivedCatalogEntry} is the one field here that no screen reads. It
 * says that cataloguing this converted file brought an entry the catalog had
 * given up on back to life, which is a change in who a duplicate analysis may
 * look at - and the batch reads it, once, to decide whether to say so. It is
 * not written to the history a conversion leaves behind, so a report rebuilt
 * from that history answers {@code false}: the row does not record it, and
 * inventing an answer would be worse than admitting the question was not asked.
 */
public record ConversionFileResult(UUID mediaId, String fileName, ConversionOutcome outcome, long originalBytes,
		long convertedBytes, long savedBytes, String savedLabel, long elapsedMillis, ConversionAdjustments adjustments,
		boolean originalQuarantined, boolean revivedCatalogEntry, String message) {
}