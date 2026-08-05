package br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Which quarantine records to clear because their file is no longer there.
 *
 * <p>
 * The list is what the screen found absent, and it is a shortlist rather than a
 * verdict: the worker looks at each file again, under its lock, and keeps any
 * record whose file turns out to be present after all. That second look is the
 * whole safety of this operation - a drive that was briefly unavailable makes
 * every item look absent at once.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QuarantineCleanupPayload(Integer schemaVersion, List<UUID> movementIds) {
}