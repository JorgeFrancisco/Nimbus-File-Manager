package br.com.jorgemelo.nimbusfilemanager.media.domain.repository.projection;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection.CameraFilter;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection.MediaScaleFilter;

/**
 * Cohesive parameter object carrying the optional media search filters bound
 * into {@code MediaSearchRepository.search}. Every field is nullable: a
 * {@code null} disables the corresponding predicate in the query.
 *
 * <p>
 * {@code requiresLocation} is three-state on purpose: {@code true} for media
 * that resolved a place, {@code false} for the ones that never did - the ones
 * worth finding in an old library - and {@code null} for "either".
 */
public record MediaSearchFilter(FileType fileType, String codec, String folder, String extension, Integer year,
		Integer month, MediaScaleFilter scale, CameraFilter camera, Boolean requiresLocation) {
}