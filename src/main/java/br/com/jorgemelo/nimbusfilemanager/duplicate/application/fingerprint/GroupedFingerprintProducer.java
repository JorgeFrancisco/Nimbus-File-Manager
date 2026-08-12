package br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint;

import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.processing.application.dto.Outcome;
import br.com.jorgemelo.nimbusfilemanager.telemetry.application.ProcessingMetrics;

/**
 * A producer whose external tool answers for several items in one invocation.
 *
 * <p>
 * Its own contract rather than a size of one on {@link FingerprintProducer},
 * because grouping changes what a unit of work is: the pool schedules groups,
 * the tool gate limits groups, and a producer that gains nothing from it - a
 * video, sampled frame by frame - keeps the item as the unit and needs no
 * opinion on any of this.
 *
 * @param <P> the pending work item
 * @param <R> the computed fingerprint the item produces
 */
interface GroupedFingerprintProducer<P, R> extends FingerprintProducer<P, R> {

	/**
	 * Computes a whole group, one outcome per item and in the same order.
	 *
	 * <p>
	 * How big a group is belongs to the engine, not here: it is the same unit it
	 * writes in, so a group is one thing to reason about - what an invocation
	 * covers, what a rejected group costs to redo, and what a transaction holds -
	 * instead of three numbers that have to be kept in step.
	 *
	 * <p>
	 * Returning outcomes rather than results is what lets the producer answer for
	 * part of a group: when the grouped invocation cannot be trusted, it falls back
	 * to computing the items one at a time, and then a single item that fails is a
	 * single failure - not a verdict on the twenty-four beside it.
	 */
	List<Outcome<P, R>> computeGroup(List<P> group, ProcessingMetrics metrics);
}