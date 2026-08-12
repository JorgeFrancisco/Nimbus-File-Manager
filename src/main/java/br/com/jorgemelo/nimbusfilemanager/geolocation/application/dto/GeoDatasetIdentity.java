package br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto;

/**
 * Who produced the dataset being installed and which version of it this is -
 * everything needed to say what the rows are, gathered from the source before
 * the import that writes them.
 *
 * <p>
 * It travels as one object because it is written as one fact: the import records
 * it in the same transaction as the boundaries, so a caller passing three of the
 * four would be describing an installation that half exists.
 */
public record GeoDatasetIdentity(String source, String version, String provider, String license) {
}