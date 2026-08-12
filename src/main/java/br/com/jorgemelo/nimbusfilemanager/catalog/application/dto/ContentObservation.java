package br.com.jorgemelo.nimbusfilemanager.catalog.application.dto;

import java.time.Instant;

/**
 * A look at a file's bytes, and where the look came from.
 *
 * @param observed what was found, whose digest must be present: a reconciliation
 * settles what a file contains, and there is no settling that without one
 * @param source the capability that went looking, for the history to be readable
 * @param occurredAt when the change was observed, not when the digest finished.
 * A file reported at nine and read at nine-thirty changed at nine, and the
 * reading is what took half an hour
 */
public record ContentObservation(ContentState observed, String source, Instant occurredAt) {
}