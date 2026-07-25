package br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto;

import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.RestoreOutcome;

/**
 * Outcome of a single restore attempt on the Quarentena screen. {@code outcome}
 * is the name of a {@link RestoreOutcome}; {@code restoredPath} is filled only
 * when the file actually moved back (which may differ from the original path
 * when the user chose a rename or an alternate folder).
 */
public record QuarantineRestoreResult(boolean success, String outcome, String message, UUID movementId,
		String restoredPath) {
}