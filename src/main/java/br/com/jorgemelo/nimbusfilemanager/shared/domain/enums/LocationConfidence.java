package br.com.jorgemelo.nimbusfilemanager.shared.domain.enums;

/**
 * Reliability of a resolved location. Thresholds (in km) are centralized in
 * LocationConfidencePolicy - do not scatter distance ranges elsewhere.
 */
public enum LocationConfidence {

	VERY_HIGH(5), HIGH(4), MEDIUM(3), LOW(2), VERY_LOW(1);

	private final int rank;

	LocationConfidence(int rank) {
		this.rank = rank;
	}

	public boolean atLeast(LocationConfidence minimum) {
		return minimum == null || rank >= minimum.rank;
	}
}