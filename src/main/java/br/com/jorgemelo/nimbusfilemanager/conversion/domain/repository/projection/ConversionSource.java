package br.com.jorgemelo.nimbusfilemanager.conversion.domain.repository.projection;

import java.util.UUID;

/**
 * The stream facts a conversion needs about the file it is about to re-encode:
 * what codec it already is (an H.265 video is never converted again) and how
 * long it is (which drives the progress bar and the post-encode validation).
 * Read separately from the entity because both live on the lazily fetched
 * {@code video} association.
 */
public record ConversionSource(UUID publicId, String videoCodec, Double durationSeconds) {
}