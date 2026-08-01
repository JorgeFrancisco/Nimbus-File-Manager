package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.Decision;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.Signals;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Reason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Verdict;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;

/**
 * Single source of truth for "which file to keep and which to mark inside a
 * duplicate (exact) or similar group". Prioritizes the <b>original</b> over
 * derivatives (WhatsApp/edited) and, crucially, <b>never marks anyone for
 * deletion when there is no clear original</b> - it only labels (KEEP /
 * DELETE_CANDIDATE / REVIEW). Nothing is deleted here; this is the
 * pre-selection/simulation the Duplicados screen renders.
 *
 * <p>
 * The services are the only callers of this policy; controllers and other
 * consumers receive the resulting verdict and reason through response DTOs.
 *
 * <p>
 * "Original" = has camera EXIF (manufacturer/model) AND its subcategory is not
 * a derivative marker. The derivative subcategories are {@code WHATSAPP},
 * {@code AIRBRUSH}, {@code PHOTOGRID} and {@code SCREENSHOT} - editor/app
 * outputs and screen captures that are never preferred over a real original
 * (Peachy/other editors currently fall into OTHER - see the media families).
 */
@Component
public class DuplicateKeepPolicy {

	private static final Set<MediaSubcategory> DERIVATIVE_SUBCATEGORIES = EnumSet.of(MediaSubcategory.WHATSAPP,
			MediaSubcategory.AIRBRUSH, MediaSubcategory.PHOTOGRID, MediaSubcategory.SCREENSHOT);

	/**
	 * @param files the whole group (keep + candidates)
	 * @param exactGroup true for byte-identical (SHA-256) groups - deletion is
	 * always safe there, so the "no clear original" guard is bypassed
	 */
	public Map<UUID, Decision> decide(List<Signals> files, boolean exactGroup) {
		Map<UUID, Decision> decisions = new HashMap<>();

		if (files == null || files.isEmpty()) {
			return decisions;
		}

		long originals = files.stream().filter(this::isOriginal).count();

		boolean confident = exactGroup || originals == 1;

		Signals best = Collections.max(files, byKeepWorth());

		for (Signals file : files) {
			if (file.id().equals(best.id())) {
				decisions.put(file.id(), new Decision(Verdict.KEEP, keepReason(best, confident)));
			} else if (confident) {
				decisions.put(file.id(), new Decision(Verdict.DELETE_CANDIDATE, derivativeReason(file, exactGroup)));
			} else {
				decisions.put(file.id(), new Decision(Verdict.REVIEW, Reason.REVIEW_NO_CLEAR_ORIGINAL));
			}
		}

		return decisions;
	}

	private boolean isOriginal(Signals file) {
		return hasIntactEmbeddedMetadata(file) && !DERIVATIVE_SUBCATEGORIES.contains(file.subcategory());
	}

	/**
	 * The original keeps its embedded capture metadata: camera EXIF (photo) or a
	 * date that came from EXIF/MEDIA_INFO (photo/video). WhatsApp and stripping
	 * editors lose it, so their date falls to the file name/filesystem.
	 */
	private boolean hasIntactEmbeddedMetadata(Signals file) {
		return file.hasCameraExif() || file.dateSource() == DateSource.EXIF
				|| file.dateSource() == DateSource.MEDIA_INFO;
	}

	private Reason keepReason(Signals best, boolean confident) {
		if (isOriginal(best)) {
			return Reason.ORIGINAL;
		}

		return confident ? Reason.BEST_IN_GROUP : Reason.REVIEW_NO_CLEAR_ORIGINAL;
	}

	private Reason derivativeReason(Signals file, boolean exactGroup) {
		if (file.subcategory() == MediaSubcategory.WHATSAPP) {
			return Reason.WHATSAPP_COPY;
		}

		if (file.subcategory() == MediaSubcategory.AIRBRUSH || file.subcategory() == MediaSubcategory.PHOTOGRID) {
			return Reason.EDITED_COPY;
		}

		return exactGroup ? Reason.IDENTICAL_COPY : Reason.DERIVATIVE;
	}

	/**
	 * Higher = keep-worthier: original, then non-derivative, then resolution, then
	 * date reliability, then oldest, then stable id. The non-derivative step
	 * matters for media without embedded metadata (e.g. audio): there
	 * {@link #isOriginal} is false for both siblings, so without it a WhatsApp copy
	 * - whose date-only name looks "older" than the real timestamp of the source -
	 * could be kept over the true original.
	 */
	private Comparator<Signals> byKeepWorth() {
		return Comparator.comparing(this::isOriginal).thenComparing(this::isNotDerivative)
				.thenComparingLong(DuplicateKeepPolicy::pixels)
				.thenComparingInt(file -> DateSource.trustOf(file.dateSource(), file.captureDate()))
				.thenComparing(Signals::captureDate, Comparator.nullsFirst(Comparator.reverseOrder()))
				.thenComparing(Signals::id, Comparator.reverseOrder());
	}

	/**
	 * A derivative (WhatsApp/edited) copy is never kept over a non-derivative
	 * sibling.
	 */
	private boolean isNotDerivative(Signals file) {
		return !DERIVATIVE_SUBCATEGORIES.contains(file.subcategory());
	}

	private static long pixels(Signals file) {
		return file.width() == null || file.height() == null ? 0L : (long) file.width() * file.height();
	}

}