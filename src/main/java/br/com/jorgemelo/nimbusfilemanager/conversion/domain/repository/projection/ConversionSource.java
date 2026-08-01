package br.com.jorgemelo.nimbusfilemanager.conversion.domain.repository.projection;

import java.time.LocalDateTime;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;

/**
 * The facts a conversion needs about the file it is about to re-encode: what
 * codec it already is (an H.265 video is never converted again), how long it is
 * (which drives the progress bar and the post-encode validation) and the
 * capture date already resolved for it (which the converted file inherits when
 * its own re-extraction can only offer a filesystem timestamp). Read separately
 * from the entity because all of it lives on lazily fetched associations.
 */
public record ConversionSource(UUID publicId, String videoCodec, Double durationSeconds, LocalDateTime captureDate,
		DateSource dateSource) {
}