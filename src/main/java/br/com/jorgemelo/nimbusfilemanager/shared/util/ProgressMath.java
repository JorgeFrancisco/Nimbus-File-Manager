package br.com.jorgemelo.nimbusfilemanager.shared.util;

/**
 * How far along something is, as a percentage, for every long-running operation
 * the interface tracks.
 *
 * <p>
 * It used to work out the time remaining as well, from the average rate since
 * the start. That answer now belongs to {@code EtaEstimator}, which measures
 * over a recent window instead - a cumulative average never forgets, so an
 * interference at the beginning of a five-hour run was still inflating the
 * estimate hours after it had passed.
 */
public final class ProgressMath {

	private ProgressMath() {
	}

	/**
	 * 0-100 with two decimals (capped), or -1 when the total is unknown.
	 *
	 * <p>
	 * Two decimals because a long queue moves too slowly for whole numbers to show
	 * anything: six thousand videos spend minutes on each percent, and a bar that
	 * never changes reads as a stalled one. The arithmetic lives here alone - three
	 * other places used to carry their own copy, each rounding differently.
	 */
	public static double percent(long done, long total) {
		if (total <= 0) {
			return -1;
		}

		if (done >= total) {
			return 100;
		}

		// Never 100 while anything is left. Two decimals make this sharper than it
		// looks: 59,999 of 60,000 rounds up to 100.00, and a bar that reads finished
		// while work continues is the one complaint every progress display earns.
		return Math.min(99.99, round(done * 100.0 / total));
	}

	/** Two decimals, the precision every progress reading in the UI carries. */
	public static double round(double percent) {
		return Math.round(percent * 100.0) / 100.0;
	}
}