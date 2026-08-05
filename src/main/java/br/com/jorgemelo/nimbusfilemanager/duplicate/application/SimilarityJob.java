package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.SimilarityMessages;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisPayload;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.SimilarityRunMode;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGrouping;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancelledException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OwnershipLostException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionMessage;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;
import lombok.extern.slf4j.Slf4j;

/**
 * One similarity analysis, from a claimed row to a published result.
 *
 * <p>
 * Shared by the photo and the video handler because the sequence is the same and
 * only the analyser differs: decode what was asked, analyse, write the result as
 * BUILDING, publish it, and only then close the execution. The two handlers stay
 * thin on purpose - what they carry is the type and the resource policy, which
 * is the part that genuinely differs between them.
 *
 * <p>
 * The request also says <em>how</em> the answer is to be reached, and the
 * sequence is the same for both routes. A rebuild compares every pair again; a
 * regroup reads the relations an earlier run approved and only places them into
 * groups. They differ in one further place, at the very end: a regroup is
 * derived from the answer that was current when it started, so it may only
 * replace that one.
 *
 * <p>
 * The execution finishes after the publication and never before. An execution
 * reported as finished while the result was still invisible would be a screen
 * saying "done" over the previous answer, with no way for anybody to tell which
 * of the two they were looking at.
 *
 * <p>
 * That same ordering is what makes this safe to cancel. Everything before the
 * publication is computation: the result is written as BUILDING only once the
 * analysis has returned, and the family's current answer is retired only by the
 * publication itself. So stopping at any point up to there leaves no row, no
 * half-written grouping and the previous answer untouched - there is nothing to
 * clean up because nothing was started. The checkpoints ride the progress
 * callback the clustering already calls once per candidate, which is the unit of
 * work: asking more often would mean asking in the middle of one comparison, and
 * asking less often would mean a cancel that waits for a whole library.
 */
@Slf4j
@Component
class SimilarityJob {

	private final SimilarityPublisher publisher;
	private final ExecutionProgressService executionProgressService;
	private final ExecutionCancellationService executionCancellationService;
	private final ExecutionPayloadCodec executionPayloadCodec;

	SimilarityJob(SimilarityPublisher publisher, ExecutionProgressService executionProgressService,
			ExecutionCancellationService executionCancellationService, ExecutionPayloadCodec executionPayloadCodec) {
		this.publisher = publisher;
		this.executionProgressService = executionProgressService;
		this.executionCancellationService = executionCancellationService;
		this.executionPayloadCodec = executionPayloadCodec;
	}

	void run(SimilarityAnalyzer analyzer, Execution execution, ClaimedExecution claimed,
			ExecutionOwnership ownership) {
		try {
			analyse(analyzer, execution, claimed, ownership);
		} catch (ExecutionCancelledException _) {
			// Nothing to undo. The grouping is written only after the analysis returns
			// and the previous one is retired only by the publication, so what the
			// screen answers with is exactly what it answered with before.
			executionProgressService.finishCommand(ownership, ExecutionStatus.CANCELLED,
					new ExecutionCounts(0, 0, 0, 0), SimilarityMessages.analysisCancelled());
		} finally {
			executionCancellationService.forget(execution.getId());
		}
	}

	private void analyse(SimilarityAnalyzer analyzer, Execution execution, ClaimedExecution claimed,
			ExecutionOwnership ownership) {
		SimilarityAnalysisPayload payload = executionPayloadCodec.decode(claimed.requestPayload(),
				SimilarityAnalysisPayload.class);

		if (payload.schemaVersion() == null
				|| payload.schemaVersion() != DuplicateConstants.SIMILARITY_PAYLOAD_SCHEMA_VERSION) {
			throw new IllegalArgumentException("Similarity payload schema " + payload.schemaVersion()
					+ " cannot be run by this version, which writes and reads schema "
					+ DuplicateConstants.SIMILARITY_PAYLOAD_SCHEMA_VERSION);
		}

		stopIfCancelled(execution);

		int minimum = payload.minSimilarityPercent() == null ? DuplicateConstants.MIN_SIMILARITY_PERCENT
				: payload.minSimilarityPercent();

		if (definitionChanged(analyzer, payload, minimum)) {
			reject(ownership, SimilarityMessages.definitionChanged());

			return;
		}

		SimilarityRunMode mode = payload.mode() == null ? SimilarityRunMode.REBUILD : payload.mode();

		if (!canRun(analyzer, mode)) {
			log.info("A {} was queued for {}, which stores no relations to work from; the execution was rejected"
					+ " rather than quietly performing a full rebuild", mode, analyzer.mediaType());

			reject(ownership, SimilarityMessages.incrementalUnavailable());

			return;
		}

		// Read before the work begins. What an incremental run concludes is about the
		// answer standing at this instant, and the promotion is the only place that
		// can find out whether it still is - so which one it was has to be remembered
		// here.
		Long basedOn = incremental(mode) ? publisher.currentAnswer(analyzer.family(minimum)) : null;

		int eligible = analyzer.eligibleCount();

		SimilarityProgressCallback progress = progressOf(execution, ownership, eligible);

		// The casts are what the guard above bought: a medium that cannot do the mode
		// asked for has already been turned away.
		SimilarityAnalysisResult result = switch (mode) {
			case REBUILD -> analyzer.analyze(minimum, progress);
			case REGROUP -> ((SimilarityRegrouper) analyzer).regroup(minimum, progress);
			case ADD -> ((SimilarityAdder) analyzer).add(minimum, progress);
		};

		reportIfTheWorldMoved(payload, result);

		// The last look, and the one that matters most: from the next line on there
		// is a grouping written down, and the line after that retires the answer the
		// screen is currently showing.
		stopIfCancelled(execution);

		publish(execution, ownership, result, mode, basedOn);
	}

	/**
	 * Writes the result down and makes it the answer - or finds out it no longer
	 * gets to be.
	 *
	 * <p>
	 * A rebuild replaces whatever is current, having recomputed everything from
	 * the fingerprints. An incremental run replaces only the answer it was derived
	 * from: it drew its conclusions from that one's relations, and publishing over
	 * a newer analysis would quietly undo the newer one's work.
	 *
	 * <p>
	 * What an arrival computed is kept either way. The relations it approved are
	 * facts about pairs of images, true whoever publishes, and the coverage that
	 * accompanies them says only that those pairs were evaluated - so a run that
	 * loses the race still leaves the next one less to do. It is the
	 * <em>grouping</em> that is discarded, because that one is an answer about a
	 * moment, and the moment passed.
	 */
	private void publish(Execution execution, ExecutionOwnership ownership, SimilarityAnalysisResult result,
			SimilarityRunMode mode, Long basedOn) {
		SimilarityGrouping grouping = publisher.build(result, execution.getId());

		boolean published = incremental(mode) ? publisher.publishIfStillBasedOn(grouping, basedOn, ownership)
				: publisher.publish(grouping, ownership);

		ExecutionMessage outcome = published
				? SimilarityMessages.analysisCompleted(result.groups().size(), result.composition().analyzedCount(),
						result.composition().eligibleCount())
				: SimilarityMessages.analysisSuperseded();

		executionProgressService.finishCommand(ownership,
				published ? ExecutionStatus.FINISHED : ExecutionStatus.FINISHED_WITH_ERRORS,
				new ExecutionCounts(result.composition().analyzedCount(), result.groups().size(), 0, 0), outcome);
	}

	/**
	 * Progress, and the checkpoint that rides it: the callback the analysis calls
	 * is where a cancellation is noticed, so the two are the same object.
	 */
	private SimilarityProgressCallback progressOf(Execution execution, ExecutionOwnership ownership, int eligible) {
		return (done, total) -> {
			stopIfCancelled(execution);

			stopIfReplaced(execution, ownership);

			executionProgressService.updateLiveProgress(ownership, total, done, 0, 0,
					SimilarityMessages.analysisStarted(total, eligible));
		};
	}

	/**
	 * Stops an analysis whose execution has been taken over, at the same place a
	 * cancellation stops it.
	 *
	 * <p>
	 * Cooperative and answered from memory: it is here to stop spending minutes on
	 * an answer nobody will accept, not to make the publication safe. What makes
	 * the publication safe is the pin the publisher takes, and this could be
	 * removed without changing what ends up on the row.
	 */
	private void stopIfReplaced(Execution execution, ExecutionOwnership ownership) {
		if (!ownership.takingIsStillCurrent()) {
			throw new OwnershipLostException(
					"Execution " + execution.getId() + " has been taken over, and this analysis will not go on");
		}
	}

	/**
	 * Whether this analyser can reach its answer the way the request asked for.
	 *
	 * <p>
	 * The two incremental modes are separate capabilities and are asked about
	 * separately, because they are separate promises: one reads relations and
	 * compares nothing, the other compares what arrived. A medium that keeps no
	 * relations can do neither, and is told so rather than being quietly given a
	 * full comparison - the two cost minutes apart, and whoever asked would have
	 * no way of telling which one they got.
	 */
	private boolean canRun(SimilarityAnalyzer analyzer, SimilarityRunMode mode) {
		return switch (mode) {
			case REBUILD -> true;
			case REGROUP -> analyzer instanceof SimilarityRegrouper;
			case ADD -> analyzer instanceof SimilarityAdder;
		};
	}

	/**
	 * Whether the result is derived from the answer that was current when the run
	 * started, and may therefore replace only that one.
	 *
	 * <p>
	 * True of both modes that read stored relations, and for the same reason: what
	 * they conclude is a statement about the state they started from. A rebuild
	 * owes that state nothing, having recomputed every relation from the
	 * fingerprints.
	 */
	private boolean incremental(SimilarityRunMode mode) {
		return mode != SimilarityRunMode.REBUILD;
	}

	private void reject(ExecutionOwnership ownership, ExecutionMessage reason) {
		executionProgressService.finishCommand(ownership, ExecutionStatus.REJECTED, new ExecutionCounts(0, 0, 0, 0),
				reason);
	}

	/**
	 * Unwinds the analysis when the user asked it to stop.
	 *
	 * <p>
	 * Thrown rather than returned, which is how every other long loop in this
	 * product stops: the clustering is several frames down and none of them has an
	 * early-return path, so an answer would have to be threaded back through each
	 * of them. The read itself is a lookup in a half-second cache, not a query.
	 */
	private void stopIfCancelled(Execution execution) {
		if (executionCancellationService.isCancelled(execution.getId())) {
			throw new ExecutionCancelledException("Similarity analysis cancelled by user.");
		}
	}

	/**
	 * Whether what this execution was asked to compute is still what it would
	 * compute.
	 *
	 * <p>
	 * The parameters are the analysis's identity: threshold, algorithm tuning,
	 * candidate cap, and the files the user hid from comparison. A request
	 * deduplicated as one piece of work must not run as another - so an execution
	 * whose definition moved underneath it ends saying that, instead of publishing
	 * an answer to a question nobody asked. Whoever changed the definition gets
	 * their analysis from the screen, which finds no published result for the new
	 * family and asks for one.
	 *
	 * <p>
	 * The composition is deliberately not checked here: that one is allowed to
	 * move.
	 */
	private boolean definitionChanged(SimilarityAnalyzer analyzer, SimilarityAnalysisPayload payload, int minimum) {
		String expected = payload.expectedParametersDigest();

		if (expected == null) {
			return false;
		}

		String current = analyzer.family(minimum).parametersDigest();

		if (expected.equals(current)) {
			return false;
		}

		log.info("The definition of the analysis changed after it was requested (expected parameters {}, now {});"
				+ " the execution was rejected rather than publishing a different analysis", expected, current);

		return true;
	}

	/**
	 * The library moved between the request and the claim - a normal condition, and
	 * worth a line in the log because it explains why two requests that looked
	 * different produced the same answer, or the reverse.
	 *
	 * <p>
	 * Nothing is re-queued and nothing is discarded: chasing a library that keeps
	 * receiving photos would never converge, and throwing away a correct analysis
	 * because a file arrived would mean never publishing one. What is published is
	 * what was analysed, which is always true about itself.
	 */
	private void reportIfTheWorldMoved(SimilarityAnalysisPayload payload, SimilarityAnalysisResult result) {
		String analysed = result.composition().digest();

		if (payload.expectedCompositionDigest() != null && !payload.expectedCompositionDigest().equals(analysed)) {
			log.info("The set of files changed between the request and the analysis; publishing what was analysed"
					+ " ({} files, digest {}) instead of what was expected (digest {})",
					result.composition().analyzedCount(), analysed, payload.expectedCompositionDigest());
		}
	}
}