package br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto;

import br.com.jorgemelo.nimbusfilemanager.geolocation.domain.enums.LocationRebuildScope;

/**
 * What a queued location rebuild carries: the scope, and nothing else.
 *
 * <p>
 * The scope is the request. "Only what has no location yet", "only what was
 * resolved with low confidence" and "everything with coordinates" are three
 * different things to ask for, and the run has to carry out the one that was
 * asked - not the one whose radio button happens to be selected when a worker
 * picks the row up.
 */
public record LocationRebuildPayload(Integer schemaVersion, LocationRebuildScope scope) {
}