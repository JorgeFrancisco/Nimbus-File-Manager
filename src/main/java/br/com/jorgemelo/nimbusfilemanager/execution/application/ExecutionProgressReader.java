package br.com.jorgemelo.nimbusfilemanager.execution.application;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionProgressModel;
import br.com.jorgemelo.nimbusfilemanager.execution.domain.enums.ProgressUnit;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import br.com.jorgemelo.nimbusfilemanager.shared.util.ProgressMath;

/**
 * How far along an execution is, asked once and answered the same way for every
 * reader.
 *
 * <p>
 * The percentage and the remaining time used to be derived independently, from
 * counters each reader picked for itself, which is how the same run came to show
 * 0% on one screen and its true progress on another. Both now come through here,
 * over the workload's declared {@link ExecutionProgressModel}, so the two
 * numbers on a screen are two views of one measurement rather than two opinions.
 */
@Component
public class ExecutionProgressReader {

	private final ExecutionProgressModels models;

	public ExecutionProgressReader(ExecutionProgressModels models) {
		this.models = models;
	}

	/** Units concluded so far, in whatever this workload counts. */
	public long done(Execution execution) {
		return models.modelFor(execution.getExecutionType()).done().of(execution);
	}

	/**
	 * The population this run set out to work through, in the same unit as
	 * {@link #done(Execution)}, or null when it never recorded one.
	 *
	 * <p>
	 * <b>It is the backlog captured for this execution, not the catalog's total.</b>
	 * Each run seeds it once, at the start, from what was pending then - so it
	 * legitimately differs between runs of the same work: one fingerprint pass was
	 * observed seeding 113.084 and the next 105.384, because the backlog had
	 * shrunk in between. Within a run it does not move, which is what makes it safe
	 * to divide by.
	 *
	 * <p>
	 * <b>That safety is a property of how the workloads behave today, not a
	 * guarantee.</b> No run currently absorbs items discovered after it started;
	 * every one of them works through the set it captured and leaves the rest to
	 * the next pass. A workload that changed that would have to rewrite this field
	 * as it goes, because a bar and an estimate dividing by a population that has
	 * stopped existing are wrong in a way nobody can see - the numbers stay
	 * plausible, they just stop describing the work.
	 *
	 * <p>
	 * The row always counts the population in whole items, so a workload measured
	 * in hundredths has its total scaled to match. Comparing a done in hundredths
	 * against a total in items is how a bar reaches a hundred per cent on its first
	 * file.
	 */
	public Long total(Execution execution) {
		Integer total = execution.getTotalExpected();

		if (total == null || total <= 0) {
			return null;
		}

		return total.longValue() * scaleOf(execution);
	}

	private long scaleOf(Execution execution) {
		return models.modelFor(execution.getExecutionType()).unit() == ProgressUnit.HUNDREDTHS ? 100 : 1;
	}

	/**
	 * 0-100 with one decimal, or null when this run has no total to divide by.
	 *
	 * <p>
	 * Null rather than zero, because the two are different statements: a bar with
	 * nothing to draw should be absent, and a bar drawn at zero says work is
	 * happening and getting nowhere.
	 */
	public Double percent(Execution execution) {
		Long total = total(execution);

		if (total == null) {
			return null;
		}

		double percent = ProgressMath.percent(done(execution), total);

		return percent < 0 ? null : Math.round(percent * 10.0) / 10.0;
	}
}