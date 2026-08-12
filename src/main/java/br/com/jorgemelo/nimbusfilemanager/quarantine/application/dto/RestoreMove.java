package br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto;

import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.MoveBaseline;

/**
 * What the physical half of a restore produced: the refusal, when it did not
 * happen, or the digest it proved when it did.
 *
 * <p>
 * Two things rather than one because the caller needs both and they never
 * arrive together. The digest is not extra work - the secure move reads the
 * file twice to verify itself - and losing it here would mean reading a file
 * the application had just finished reading.
 *
 * @param failure the result to report, or null when the move succeeded
 * @param baseline what the move proved about the bytes, or null when it failed
 */
public record RestoreMove(QuarantineRestoreResult failure, MoveBaseline baseline) {
}