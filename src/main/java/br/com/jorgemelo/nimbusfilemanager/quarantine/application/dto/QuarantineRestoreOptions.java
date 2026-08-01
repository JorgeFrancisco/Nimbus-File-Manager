package br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto;

import java.nio.file.Path;

import br.com.jorgemelo.nimbusfilemanager.quarantine.domain.enums.ConflictResolution;

/**
 * Per-file restore instructions gathered from the Quarentena screen.
 *
 * @param destinationFolder an alternate folder to restore into (used when the
 * original folder vanished, or when the user simply wants a different target);
 * {@code null} restores to the original path.
 * @param conflictResolution what to do if a file already exists at the
 * destination.
 */
public record QuarantineRestoreOptions(Path destinationFolder, ConflictResolution conflictResolution) {

	public static QuarantineRestoreOptions defaults() {
		return new QuarantineRestoreOptions(null, ConflictResolution.BLOCK);
	}

	public QuarantineRestoreOptions {
		if (conflictResolution == null) {
			conflictResolution = ConflictResolution.BLOCK;
		}
	}
}