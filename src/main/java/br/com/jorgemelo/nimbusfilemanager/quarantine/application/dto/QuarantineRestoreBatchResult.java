package br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto;

import java.util.List;

/**
 * Aggregated outcome of restoring a selection of quarantined files at once. The
 * easy cases restore immediately; items the worker found already decided
 * against - a name collision at the original path, a vanished origin folder -
 * come back as {@code conflicts}/{@code originMissing} and are left in
 * quarantine for the user to resolve one by one.
 */
public record QuarantineRestoreBatchResult(boolean success, int total, int restored, int conflicts, int originMissing,
		int errors, String message, List<QuarantineRestoreResult> items) {
}