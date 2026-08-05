package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.SimilarityMessages;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisPayload;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGrouping;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancelledException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionCounts;
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

	void run(SimilarityAnalyzer analyzer, Execution execution, ClaimedExecution claimed) {
		try {
			analyse(analyzer, execution, claimed);
		} catch (ExecutionCancelledException _) {
			// Nothing to undo. The grouping is written only after the analysis returns
			// and the previous one is retired only by the publication, so what the
			// screen answers with is exactly what it answered with before.
			executionProgressService.finishCommand(execution, ExecutionStatus.CANCELLED,
					new ExecutionCounts(0, 0, 0, 0), SimilarityMessages.analysisCancelled());
		} finally {
			executionCancellationService.forget(execution.getId());
		}
	}

	private void analyse(SimilarityAnalyzer analyzer, Execution execution, ClaimedExecution claimed) {
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
			executionProgressService.finishCommand(execution, ExecutionStatus.REJECTED, new ExecutionCounts(0, 0, 0, 0),
					SimilarityMessages.definitionChanged());

			return;
		}

		int eligible = analyzer.eligibleCount();

		SimilarityAnalysisResult result = analyzer.analyze(minimum, (done, total) -> {
			stopIfCancelled(execution);

			executionProgressService.updateLiveProgress(execution, total, done, 0, 0,
					SimilarityMessages.analysisStarted(total, eligible));
		});

		reportIfTheWorldMoved(payload, result);

		// The last look, and the one that matters most: from the next line on there
		// is a grouping written down, and the line after that retires the answer the
		// screen is currently showing.
		stopIfCancelled(execution);

		SimilarityGrouping grouping = publisher.build(result, execution.getId());

		boolean published = publisher.publish(grouping);

		executionProgressService.finishCommand(execution,
				published ? ExecutionStatus.FINISHED : ExecutionStatus.FINISHED_WITH_ERRORS,
				new ExecutionCounts(result.composition().analyzedCount(), result.groups().size(), 0, 0),
				SimilarityMessages.analysisCompleted(result.groups().size(), result.composition().analyzedCount(),
						result.composition().eligibleCount()));
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