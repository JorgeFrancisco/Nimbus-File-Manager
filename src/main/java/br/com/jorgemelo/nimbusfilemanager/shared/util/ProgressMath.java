package br.com.jorgemelo.nimbusfilemanager.shared.util;

/**
 * Progress arithmetic shared by every long-running operation the UI tracks
 * (dataset download and import, location rebuild, ...): percentage complete and
 * estimated time remaining from the average rate so far. Project standard:
 * visible progress always offers bar + percentage + time remaining whenever a
 * total is known; only the counter is shown when it is not.
 */
public final class ProgressMath {

	/** Below this elapsed time the average rate is too noisy for an estimate. */
	static final long MIN_ELAPSED_FOR_ETA_MILLIS = 2_000;

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

	/** Seconds remaining by average rate, or -1 when it cannot be estimated. */
	public static long etaSeconds(long elapsedMillis, long done, long total) {
		if (total <= 0 || done <= 0 || done > total || elapsedMillis < MIN_ELAPSED_FOR_ETA_MILLIS) {
			return -1;
		}

		return Math.max(0, elapsedMillis * (total - done) / done / 1000);
	}
}