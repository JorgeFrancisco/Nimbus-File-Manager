package br.com.jorgemelo.nimbusfilemanager.shared.application.dto;

import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementStatus;

/**
 * An operation that is on record and can be resumed.
 *
 * <p>
 * What a caller reads back from preparing, whether this attempt wrote it or an
 * earlier one did. The two identities are the point: {@code movementPublicId}
 * names the operation, {@code catalogFileEventPublicId} names the fact it will
 * record if it gets that far - and both were decided before the file system was
 * touched, so a retry uses them rather than inventing new ones.
 *
 * @param status what a previous attempt already concluded, if one did.
 * Anything other than {@code PENDING} means this operation is settled and must
 * not be attempted again
 */
public record PreparedMovement(long id, UUID movementPublicId, UUID catalogFileEventPublicId, Long catalogFileId,
		String requestedSourcePath, String requestedTargetPath, MovementStatus status) {
}