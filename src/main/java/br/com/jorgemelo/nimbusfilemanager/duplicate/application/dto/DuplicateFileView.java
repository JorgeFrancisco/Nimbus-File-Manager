package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.SizeResponse;

/**
 * View-model for a single file inside a group on the Duplicados screen - wraps
 * {@code DuplicateCandidateFileResponse} (shared with the REST API) with
 * display-only fields (thumbnail/icon, "keep" flag) so {@code duplicates.html}
 * can render both the "Exatos" and "Fotos semelhantes" tabs with the exact same
 * markup, in any view mode (details table or icon grid), without needing a
 * fileType-to-icon decision in the template itself.
 *
 * <p>
 * Pure data carrier: every presentation decision (preview support, lightbox
 * class, localized icon/open/date labels and the date-source badge tier) is
 * precomputed by {@code DuplicatesWebController} so the record stays free of
 * behavior and hard-coded text.
 */
public record DuplicateFileView(

		UUID id, String fileName, String currentFolder, String currentPath, SizeResponse size, Instant modifiedAt,

		// System's best date, shown in the "Data" column next to the source badge.
		LocalDateTime captureDate,

		boolean keep,

		// Identity of the group's recommended "keep" photo (the one the group selector
		// chose to keep),
		// independent of the keep-policy verdict. Drives "Sugerir seleção": for
		// similar-but-not-identical
		// groups the policy labels everything "Revisar" (keep=true for all), so this is
		// what lets the
		// button still mark the non-kept candidates.
		boolean recommendedKeep,

		boolean image, boolean video, boolean pdf, boolean text, boolean audio, String previewUrl, String contentUrl,

		String iconClass, String iconLabel,

		// Recommendation transparency: highlight is the Reason enum name (or null),
		// kept for the CSS tier only. highlightLabel is the localized badge text,
		// reason is the tooltip text (why it is recommended), and resolution is "W × H"
		// or null.
		String highlight, String highlightLabel, String reason, String resolution,

		// Precomputed presentation: preview support, lightbox CSS class, the localized
		// "open" tooltip, and the localized date-origin label plus its badge tier.
		boolean previewable, String lightboxClass, String openTitle, String dateSourceLabel,
		String dateSourceBadgeClass,

		// Human-friendly renderings of the dates above, so the template only displays.
		String captureDateLabel, String modifiedAtLabel,

		// Whether the screen may offer actions over this file. A published similarity
		// result is a photograph of an analysis, and a file deleted or quarantined
		// since then stays in the group it was found in - what must not happen is the
		// screen offering to delete something that is already gone. The backend
		// decides it; the template only reads the flag, as the front-end rules
		// require.
		boolean actionable) {

	/**
	 * The shape the exact-duplicates tab builds, where every listed file is by
	 * definition current: those groups are computed from the catalog at request
	 * time, so there is no gap between what was found and what exists.
	 */
	public DuplicateFileView(UUID id, String fileName, String currentFolder, String currentPath, SizeResponse size,
			Instant modifiedAt, LocalDateTime captureDate, boolean keep, boolean recommendedKeep, boolean image,
			boolean video, boolean pdf, boolean text, boolean audio, String previewUrl, String contentUrl,
			String iconClass, String iconLabel, String highlight, String highlightLabel, String reason,
			String resolution, boolean previewable, String lightboxClass, String openTitle, String dateSourceLabel,
			String dateSourceBadgeClass, String captureDateLabel, String modifiedAtLabel) {
		this(id, fileName, currentFolder, currentPath, size, modifiedAt, captureDate, keep, recommendedKeep, image,
				video, pdf, text, audio, previewUrl, contentUrl, iconClass, iconLabel, highlight, highlightLabel,
				reason, resolution, previewable, lightboxClass, openTitle, dateSourceLabel, dateSourceBadgeClass,
				captureDateLabel, modifiedAtLabel, true);
	}
}