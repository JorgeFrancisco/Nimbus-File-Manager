package br.com.jorgemelo.nimbusfilemanager.execution.application.dto;

import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ProgressDone;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ProgressUnit;

/**
 * What a workload's progress means: which counter is "done", what a unit is, and
 * whether a remaining time can honestly be derived from them.
 *
 * @param done which counter holds the concluded count
 * @param unit what one of them is
 * @param etaApplicable whether a single rate over these units predicts the end.
 * False is a statement about the work, not a gap in the implementation
 */
public record ExecutionProgressModel(ProgressDone done, ProgressUnit unit, boolean etaApplicable) {

	public static ExecutionProgressModel files(ProgressDone done) {
		return new ExecutionProgressModel(done, ProgressUnit.FILES, true);
	}

	public static ExecutionProgressModel items(ProgressDone done) {
		return new ExecutionProgressModel(done, ProgressUnit.ITEMS, true);
	}

	/**
	 * Progress that counts, over units that cannot be averaged. The bar is honest -
	 * six of nine stages really are behind us - and the estimate is not.
	 */
	public static ExecutionProgressModel withoutEstimate(ProgressDone done, ProgressUnit unit) {
		return new ExecutionProgressModel(done, unit, false);
	}
}