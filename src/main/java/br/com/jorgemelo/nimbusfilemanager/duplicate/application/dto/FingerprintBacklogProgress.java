package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.EtaEstimate;

/**
 * What the fingerprint panel shows, in one small answer it can ask for on its
 * own.
 *
 * <p>
 * It exists because this is <em>not</em> execution activity: the counts describe
 * the catalog's backlog and are true with nothing running at all - which is how
 * the screen knows there is work left to ask for. Putting them on
 * {@code /api/execution-activity} would have attached catalog statistics to a
 * contract that means "work in flight", and made an endpoint every page polls
 * pay for four counting queries.
 *
 * <p>
 * The panel used to keep itself current by re-fetching the whole Duplicates
 * page every four seconds, which on a large library is seconds of server work
 * per cycle. This is the same information at a fraction of the cost.
 *
 * @param eta the fact: how much longer, or which kind of "no answer" this is.
 * What the polling client reads, and what it formats for itself
 * @param etaLabel the same fact already worded, for the first render - the
 * server draws this panel before any poll has happened, and a template has no
 * formatter of its own. Both come from one place, which is what stopped the page
 * and the poll disagreeing about the same number
 * @param other the other medium's fingerprint when it is the one running, and
 * {@code null} otherwise - context for a backlog that is standing still, added
 * here rather than fetched separately so the poll that already keeps this panel
 * current keeps that line current too
 */
public record FingerprintBacklogProgress(long pending, long done, long failed, long total, double percent,
		EtaEstimate eta, String etaLabel, boolean running, OtherFingerprintProgress other) {

	public static FingerprintBacklogProgress of(FingerprintBacklogStatus status, EtaEstimate eta, String etaLabel,
			boolean running, OtherFingerprintProgress other) {
		return new FingerprintBacklogProgress(status.pending(), status.done(), status.failed(), status.total(),
				status.percent(), eta, etaLabel, running, other);
	}
}