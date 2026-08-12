package br.com.jorgemelo.nimbusfilemanager.catalog.application.dto;

import java.time.Instant;

/**
 * Which file to look at, and nothing about what was seen.
 *
 * <p>
 * Deliberately not a snapshot of the observation that asked for it. The
 * execution means "find out what this file contains now", so a burst of
 * notifications about one file collapses into one piece of work whose answer is
 * still correct when it runs - whereas an execution carrying the size and
 * timestamp of the notification that created it would be answering a question
 * about a moment that has passed.
 */
public record ContentVerificationPayload(Integer schemaVersion, Long catalogFileId, Instant observedAt) {

	public static final int SCHEMA_VERSION = 1;
}