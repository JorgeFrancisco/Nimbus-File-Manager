package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

/**
 * Live progress of a visual fingerprint backlog (photo or video), derived from
 * the DB counts. {@code failed} counts only items that exhausted their attempts
 * (they no longer block the screen); items still being retried count as
 * {@code pending}. The matching similarity tab is blocked while
 * {@link #blocking()} (pending &gt; 0) and unblocks once pending reaches 0,
 * even if there are failures.
 */
public record FingerprintBacklogStatus(long pending, long done, long failed) {

	public long total() {
		return pending + done + failed;
	}

	public boolean blocking() {
		return pending > 0;
	}

	/** 0-100 completion, counting done + failed as terminal against the total. */
	public int percent() {
		long total = total();

		return total == 0 ? 100 : (int) Math.round((done + failed) * 100.0 / total);
	}
}