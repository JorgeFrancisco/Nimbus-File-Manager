package br.com.jorgemelo.nimbusfilemanager.metadata.application.dto;

import java.time.Instant;

/**
 * Result of a single combined filesystem attribute read, carrying both the
 * created and modified dates so callers avoid issuing two separate {@code stat}
 * calls per file.
 *
 * <p>
 * Instants, because that is what the filesystem reports: a {@code FileTime} is a
 * position on the timeline, and flattening it to a local date-time would throw
 * away the offset it was recorded with. A capture date is the opposite kind of
 * value and is not carried here.
 */
public record FileSystemDates(Instant createdAt, Instant modifiedAt) {
}