package br.com.jorgemelo.nimbusfilemanager.execution.application.dto;

import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.EtaState;

/**
 * The one answer every screen asks for: how much longer, or which kind of "no
 * answer" this is.
 *
 * <p>
 * The seconds are the fact and stay a number: the backend decides how long is
 * left, and the interface decides how that reads in the reader's language and at
 * the reader's precision. A localized string here would have made the backend
 * the authority on wording too, which is how three vocabularies with different
 * granularities came to exist for the same quantity.
 *
 * @param state whether there is an estimate, and why not when there is not
 * @param remainingSeconds filled only when the state is
 * {@link EtaState#AVAILABLE}
 */
public record EtaEstimate(EtaState state, Long remainingSeconds) {

	private static final EtaEstimate CALCULATING = new EtaEstimate(EtaState.CALCULATING, null);
	private static final EtaEstimate NOT_APPLICABLE = new EtaEstimate(EtaState.NOT_APPLICABLE, null);

	public static EtaEstimate of(long remainingSeconds) {
		return new EtaEstimate(EtaState.AVAILABLE, Math.max(0, remainingSeconds));
	}

	public static EtaEstimate calculating() {
		return CALCULATING;
	}

	public static EtaEstimate notApplicable() {
		return NOT_APPLICABLE;
	}
}