package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;

/**
 * Single home for clamping a requested similarity threshold into the range the
 * Fotos Semelhantes screen accepts. It lived duplicated across both similarity
 * services and the web controller; since a looser or out-of-range threshold
 * could surface (and delete) less-similar media than intended, the bounds are
 * enforced in one place. A null request means "no explicit value" and resolves
 * to the floor.
 */
public final class SimilarityBounds {

	private SimilarityBounds() {
	}

	public static int clamp(Integer requested) {
		if (requested == null) {
			return DuplicateConstants.MIN_SIMILARITY_PERCENT;
		}

		return Math.clamp(requested, DuplicateConstants.MIN_SIMILARITY_PERCENT,
				DuplicateConstants.MAX_SIMILARITY_PERCENT);
	}
}