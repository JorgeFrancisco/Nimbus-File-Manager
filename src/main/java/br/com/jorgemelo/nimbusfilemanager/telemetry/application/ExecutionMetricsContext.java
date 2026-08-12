package br.com.jorgemelo.nimbusfilemanager.telemetry.application;

/**
 * Where one execution - and no other - accumulates what it costs.
 *
 * <p>
 * <b>Why this exists.</b> The two accumulators inside were Spring singletons,
 * cleared by a {@code reset()} at the start of a run, under a premise written
 * into their own comments: that executions never overlap. They do. A photo and a
 * video fingerprint were observed starting in the same second, both driving the
 * same {@code ProcessingCoordinator}, and only the inventory ever called
 * {@code reset()} - so a snapshot taken at the end of either one was the sum of
 * both, plus whatever the last inventory had left behind. The numbers looked
 * plausible and meant nothing.
 *
 * <p>
 * <b>What replaces the premise.</b> An instance per execution, handed to the
 * work that accumulates into it. Two executions cannot share one because there
 * is no shared one to find: nothing is static, nothing is a bean, and the only
 * way to reach an accumulator is to be given it. Ending, cancelling or failing
 * one execution cannot touch another's numbers, because it has no reference to
 * them.
 *
 * <p>
 * <b>Two accumulators and not one</b>, because they answer different questions
 * and their arithmetic disagrees on purpose. {@link ExecutionPhaseTimings}
 * partitions the run: the phases are consecutive, and their sum approaches the
 * wall clock. {@link ProcessingMetrics} counts work that happens in parallel, so
 * its sums are expected to <em>exceed</em> the wall clock - several ffmpeg
 * processes running at once is the normal case, not double counting. Merging
 * them into one bag would lose exactly that distinction.
 *
 * <p>
 * <b>Not an authorization.</b> This says where an attempt accumulated; it does
 * not say whether that attempt may still write those numbers down. That is
 * {@code ExecutionOwnership}, and consolidation asks it separately - an attempt
 * that lost its claim has a perfectly valid context full of numbers nobody
 * wants.
 */
public final class ExecutionMetricsContext {

	private final ProcessingMetrics processingMetrics = new ProcessingMetrics();
	private final ExecutionPhaseTimings phaseTimings = new ExecutionPhaseTimings();

	public ProcessingMetrics processing() {
		return processingMetrics;
	}

	public ExecutionPhaseTimings phases() {
		return phaseTimings;
	}
}