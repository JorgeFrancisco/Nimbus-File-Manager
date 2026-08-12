package br.com.jorgemelo.nimbusfilemanager.organization.application.dto;

/**
 * A catalogued file the walk did not find, and where the catalog last had it.
 *
 * <p>
 * The path travels with the identifier because the pass has two things to do
 * with it and they need different halves: recording that it is gone needs the
 * identifier, and asking whether its bytes turned up elsewhere needs the place
 * it left.
 */
public record MissingFile(Long catalogFileId, String currentPath) {
}