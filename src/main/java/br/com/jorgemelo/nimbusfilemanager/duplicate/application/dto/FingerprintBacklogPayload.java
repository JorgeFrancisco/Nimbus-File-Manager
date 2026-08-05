package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

/**
 * What a queued fingerprint backlog carries.
 *
 * <p>
 * Almost nothing, and that is the shape of the work: a backlog is a query, not a
 * list, so a run has no items to carry. Which backlog it is comes from the
 * execution's own type.
 *
 * @param rebuild discard the fingerprints of this kind before draining. A retry
 * discards again - the outcome is identical and only the partial work of the
 * attempt that died is repeated, which is what "redo everything" already meant
 */
public record FingerprintBacklogPayload(Integer schemaVersion, Boolean rebuild) {

	public boolean rebuildValue() {
		return rebuild != null && rebuild;
	}
}