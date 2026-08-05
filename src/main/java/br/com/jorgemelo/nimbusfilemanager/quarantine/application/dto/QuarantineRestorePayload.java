package br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Which quarantined items to put back, and - when one file is restored on its
 * own - exactly where.
 *
 * <p>
 * Restoring one file is still a conversation: the screen asks what to do about
 * a name collision or a missing origin folder, and the person answers. What
 * changed is when. The conversation is held before anything is queued, and what
 * travels here is its conclusion - a destination already decided - so the
 * worker carries out an intention instead of discovering a question it has
 * nobody to ask.
 *
 * @param destination the exact file the single restore must create, or
 * {@code null} for a batch, which puts each item back at its own origin under
 * the safe defaults
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QuarantineRestorePayload(Integer schemaVersion, List<UUID> movementIds, String destination) {
}