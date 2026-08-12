package br.com.jorgemelo.nimbusfilemanager.shared.application.dto;

import java.nio.file.Path;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MovementReason;

/**
 * One operation a caller is about to attempt, before anything is touched.
 *
 * @param reason why this operation exists, when the caller already knows -
 * quarantining says so from the start. Null where the reason is an outcome
 * rather than an intent, which is most of them
 */
public record MovementRequest(Long catalogFileId, Path requestedSource, Path requestedTarget, MovementReason reason) {
}