package br.com.jorgemelo.nimbusfilemanager.catalog.application.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The retention window a queued catalog purge must apply.
 *
 * <p>
 * The window travels, the cutoff does not: what is overdue has to be decided
 * when the purge runs, not when it is queued, or a request waiting behind a
 * long conversion would remove rows by yesterday's clock. The same reason the
 * quarantine purge carries its days rather than its list.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogPurgePayload(Integer schemaVersion, Integer retentionDays) {
}