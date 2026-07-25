package br.com.jorgemelo.nimbusfilemanager.settings.application.dto;

/**
 * A single time-zone choice offered by the settings screen combo: a
 * human-friendly pt-BR {@code label} mapped to its IANA {@code id} (the value
 * actually stored). See
 * {@link br.com.jorgemelo.nimbusfilemanager.settings.application.AppTimeZones}.
 */
public record Option(String id, String label) {
}