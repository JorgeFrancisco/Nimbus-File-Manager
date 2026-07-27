package br.com.jorgemelo.nimbusfilemanager.quarantine.application.dto;

/**
 * What the screen is told after a permanent delete. The counters are there for
 * the status line; {@code message} is the sentence shown when the delete did
 * not do what was asked, already localized, so the screen never has to work out
 * why from the numbers - it was a status line reading "0 deleted, 0 errors"
 * that made a delete blocked by a running conversion look like a success.
 *
 * @param message null when everything asked for was deleted, and there is
 *                nothing to interrupt the user with.
 */
public record QuarantineDeleteResponse(int purged, int catalogsFreed, int skipped, int busy, int errors,
		String message) {
}