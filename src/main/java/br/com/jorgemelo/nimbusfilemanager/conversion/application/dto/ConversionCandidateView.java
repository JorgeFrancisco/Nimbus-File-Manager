package br.com.jorgemelo.nimbusfilemanager.conversion.application.dto;

import java.util.UUID;

/**
 * One convertible video as the Conversão screen renders it. Every field is
 * already display-ready (localized codec label, formatted size and duration),
 * so the template never formats or translates anything itself.
 *
 * <p>
 * {@code name}, {@code mediaPublicId}, {@code previewUrl}, the kind flags and
 * the icon accessors are the contract of the shared media card
 * ({@code fragments/media-cards}), which is what gives this screen the same
 * thumbnail and lightbox player every other media screen already has.
 */
public record ConversionCandidateView(UUID mediaPublicId, String name, String folder, String path, long sizeBytes,
		String sizeLabel, String codecLabel, String containerLabel, String durationLabel, String resolutionLabel,
		boolean image, boolean video, boolean pdf, boolean text, boolean audio, String previewUrl, String iconClass,
		String iconLabelKey) {
}