package br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * What to expunge for good, and why it was asked for.
 *
 * <p>
 * Two shapes in one payload because there are two ways to ask and one loop that
 * answers: the daily pass names a retention window and lets the purge find what
 * is overdue, and a person names the items directly. Exactly one of the two is
 * ever set, and the handler reads whichever it is.
 *
 * @param retentionDays the window of the scheduled pass, or {@code null} when a
 * person picked the items
 * @param movementIds what a person picked, or {@code null} for the daily pass
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QuarantinePurgePayload(Integer schemaVersion, Integer retentionDays, List<UUID> movementIds) {
}