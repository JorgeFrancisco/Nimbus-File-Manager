package br.com.jorgemelo.nimbusfilemanager.geolocation.application.dto;

/**
 * What a queued dataset update carries: the schema, and nothing else.
 *
 * <p>
 * The source URLs, the levels and the provider live in the settings and are read
 * when the run happens, not when it is asked for - and that is deliberate, not
 * an omission. Unlike a similarity analysis, whose parameters <em>are</em> the
 * question ("group at 85%" must not silently become "group at 90%"), what is
 * being asked here is "bring the dataset up to date with the configured source".
 * Pinning the URL at enqueue time would make a queued update fetch from an
 * address the administrator has since corrected, which is the opposite of what
 * they asked for.
 */
public record GeoDatasetPayload(Integer schemaVersion) {
}