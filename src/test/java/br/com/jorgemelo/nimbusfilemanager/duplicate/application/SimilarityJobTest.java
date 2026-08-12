package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.FingerprintAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.SimilarityConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedMember;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisPayload;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityComposition;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityFamily;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.GroupingStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Reason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.SimilarityRunMode;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Verdict;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGrouping;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionOwnership;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.OwnershipLostException;
import br.com.jorgemelo.nimbusfilemanager.execution.application.Takings;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ClaimedExecution;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * A queued analysis can wait an arbitrarily long time before a worker takes it,
 * and the library it was asked about may not be the library it will run over.
 * These tests pin what the job does about that: it refuses to publish an
 * analysis whose <em>definition</em> changed, and it publishes - reporting the
 * difference - when only the <em>set of files</em> moved.
 */
class SimilarityJobTest {

	private static final String PARAMETERS = "p".repeat(64);
	private static final String COMPOSITION = "c".repeat(64);

	private final SimilarityPublisher publisher = mock(SimilarityPublisher.class);
	private final ExecutionProgressService executionProgressService = mock(ExecutionProgressService.class);
	private final ExecutionPayloadCodec executionPayloadCodec = new ExecutionPayloadCodec(new ObjectMapper());
	private final SimilarityAnalyzer analyzer = mock(SimilarityAnalyzer.class);

	/**
	 * An analyser that also stores relations, which is what the photo one is. The
	 * capabilities are interfaces of their own rather than methods on the first,
	 * so the mock has to carry all three - and a plain {@link #analyzer} is then
	 * exactly the medium that can do neither of the incremental modes.
	 */
	private final SimilarityAnalyzer relationKeeper = mock(SimilarityAnalyzer.class,
			withSettings().extraInterfaces(SimilarityRegrouper.class, SimilarityAdder.class));

	private final ExecutionCancellationService executionCancellationService = mock(ExecutionCancellationService.class);

	private final SimilarityJob job = new SimilarityJob(publisher, executionProgressService,
			executionCancellationService, executionPayloadCodec);

	private final Execution execution = Execution.builder().id(42L).executionType(ExecutionType.SIMILARITY_PHOTO)
			.build();

	private final ExecutionOwnership ownership = Takings.owning(42L);

	@BeforeEach
	void defaults() {
		when(analyzer.family(anyInt())).thenReturn(new SimilarityFamily(FileType.PHOTO,
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1, SimilarityConstants.GROUPING_VERSION, PARAMETERS));
		when(analyzer.eligibleCount()).thenReturn(120);
		when(analyzer.analyze(anyInt(), any())).thenReturn(result(COMPOSITION));
		when(analyzer.mediaType()).thenReturn(FileType.VIDEO);

		when(relationKeeper.family(anyInt())).thenReturn(new SimilarityFamily(FileType.PHOTO,
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1, SimilarityConstants.GROUPING_VERSION, PARAMETERS));
		when(relationKeeper.eligibleCount()).thenReturn(120);
		when(relationKeeper.analyze(anyInt(), any())).thenReturn(result(COMPOSITION));
		when(regrouper().regroup(anyInt(), any())).thenReturn(result(COMPOSITION));
		when(adder().add(anyInt(), any())).thenReturn(result(COMPOSITION));

		when(publisher.build(any(), any())).thenReturn(grouping());
		when(publisher.publish(any(), any())).thenReturn(true);
		when(publisher.publishIfStillBasedOn(any(), any(), any())).thenReturn(true);
	}

	@Test
	void aPayloadWrittenByAnotherSchemaIsNotRun() {
		String payload = executionPayloadCodec
				.encode(new SimilarityAnalysisPayload(99, 70, PARAMETERS, COMPOSITION, SimilarityRunMode.REBUILD));

		ClaimedExecution claimed = claimed(payload);

		Assertions.assertThatThrownBy(() -> job.run(analyzer, execution, claimed, ownership))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schema");

		verify(analyzer, never()).analyze(anyInt(), any());
	}

	/**
	 * A payload that names no schema at all is refused for the same reason as one
	 * naming the wrong number: nothing says what its fields mean, and guessing is
	 * how a request gets carried out as something else.
	 */
	@Test
	void aPayloadThatNamesNoSchemaIsNotRun() {
		String payload = executionPayloadCodec
				.encode(new SimilarityAnalysisPayload(null, 70, PARAMETERS, COMPOSITION, SimilarityRunMode.REBUILD));

		ClaimedExecution claimed = claimed(payload);

		Assertions.assertThatThrownBy(() -> job.run(analyzer, execution, claimed, ownership))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schema");

		verify(analyzer, never()).analyze(anyInt(), any());
	}

	@Test
	void aRequestWhoseDefinitionChangedIsRejectedRatherThanAnsweredDifferently() {
		String payload = executionPayloadCodec.encode(
				new SimilarityAnalysisPayload(DuplicateConstants.SIMILARITY_PAYLOAD_SCHEMA_VERSION, 70,
						"stale".repeat(12) + "0000", COMPOSITION, SimilarityRunMode.REBUILD));

		job.run(analyzer, execution, claimed(payload), ownership);

		verify(executionProgressService).finishCommand(eq(ownership), eq(ExecutionStatus.REJECTED), any(), any());
		verify(analyzer, never()).analyze(anyInt(), any());
		verify(publisher, never()).build(any(), any());
	}

	@Test
	void aSetOfFilesThatMovedIsStillPublished() {
		when(analyzer.analyze(anyInt(), any())).thenReturn(result("moved".repeat(12) + "0000"));

		job.run(analyzer, execution, claimed(validPayload()), ownership);

		verify(publisher).build(any(), eq(42L));
		verify(publisher).publish(any(), any());
		verify(executionProgressService).finishCommand(eq(ownership), eq(ExecutionStatus.FINISHED), any(), any());
	}

	@Test
	void aPublicationThatLostTheRaceFinishesWithErrors() {
		when(publisher.publish(any(), any())).thenReturn(false);

		job.run(analyzer, execution, claimed(validPayload()), ownership);

		verify(executionProgressService).finishCommand(eq(ownership), eq(ExecutionStatus.FINISHED_WITH_ERRORS), any(),
				any());
	}

	@Test
	void aPayloadWithoutAThresholdFallsBackToTheDocumentedDefault() {
		String payload = executionPayloadCodec.encode(new SimilarityAnalysisPayload(
				DuplicateConstants.SIMILARITY_PAYLOAD_SCHEMA_VERSION, null, null, null, null));

		job.run(analyzer, execution, claimed(payload), ownership);

		ArgumentCaptor<Integer> minimum = ArgumentCaptor.forClass(Integer.class);

		verify(analyzer).analyze(minimum.capture(), any());

		Assertions.assertThat(minimum.getValue()).isEqualTo(DuplicateConstants.MIN_SIMILARITY_PERCENT);
	}

	@Test
	void progressIsReportedAgainstWhatIsBeingAnalysedNotAgainstTheWholeLibrary() {
		when(analyzer.analyze(anyInt(), any())).thenAnswer(call -> {
			SimilarityProgressCallback progress = call.getArgument(1);

			progress.update(30, 118);

			return result(COMPOSITION);
		});

		job.run(analyzer, execution, claimed(validPayload()), ownership);

		verify(executionProgressService).updateLiveProgress(eq(ownership), eq(118), eq(30), eq(0), eq(0), any());
	}

	/**
	 * The same tick that notices a cancellation notices a row that changed hands,
	 * and stops the analysis where it stands.
	 *
	 * <p>
	 * Minutes of comparing, for an answer the publisher's pin would refuse: this
	 * saves the minutes and nothing else. Nothing is published, and no outcome is
	 * written about a row that belongs to somebody else now.
	 */
	@Test
	void anAnalysisWhoseRowWasTakenOverStopsAtTheNextTick() {
		ExecutionOwnership replaced = Takings.replaced(42L);

		when(analyzer.analyze(anyInt(), any())).thenAnswer(call -> {
			SimilarityProgressCallback progress = call.getArgument(1);

			progress.update(30, 118);

			return result(COMPOSITION);
		});

		ClaimedExecution claimed = claimed(validPayload());

		Assertions.assertThatThrownBy(() -> job.run(analyzer, execution, claimed, replaced))
				.isInstanceOf(OwnershipLostException.class).hasMessageContaining("taken over");

		verify(publisher, never()).build(any(), any());
		verify(publisher, never()).publish(any(), any());

		verify(executionProgressService, never()).finishCommand(any(), any(), any(), any());
	}

	/**
	 * A regroup reads what an earlier run approved instead of comparing anything
	 * again. That is the entire difference between the two modes, and it is what
	 * makes a removal cost the grouping rather than the library.
	 */
	@Test
	void aRegroupReadsTheStoredRelationsInsteadOfComparingAgain() {
		job.run(relationKeeper, execution, claimed(regroupPayload()), ownership);

		verify(regrouper()).regroup(eq(70), any());
		verify(relationKeeper, never()).analyze(anyInt(), any());
		verify(executionProgressService).finishCommand(eq(ownership), eq(ExecutionStatus.FINISHED), any(), any());
	}

	/**
	 * The answer a regroup is derived from is read <em>before</em> the work, and
	 * that ordering is the guard: read afterwards it would name whatever is current
	 * at the end, which is exactly the newer analysis the check exists to notice.
	 */
	@Test
	void theAnswerARegroupIsDerivedFromIsReadBeforeTheWorkAndNotAfterIt() {
		when(publisher.currentAnswer(any())).thenReturn(7L);

		InOrder order = inOrder(publisher, relationKeeper);

		job.run(relationKeeper, execution, claimed(regroupPayload()), ownership);

		order.verify(publisher).currentAnswer(any());
		order.verify(regrouper()).regroup(anyInt(), any());
		order.verify(publisher).build(any(), any());

		verify(publisher).publishIfStillBasedOn(any(), eq(7L), any());
		verify(publisher, never()).publish(any(), any());
	}

	/**
	 * And when that answer was replaced meanwhile, this one does not take its
	 * place: the newer analysis knows about files this one drew no conclusion
	 * about.
	 */
	@Test
	void aRegroupWhoseBaseWasReplacedDoesNotBecomeTheAnswer() {
		when(publisher.publishIfStillBasedOn(any(), any(), any())).thenReturn(false);

		job.run(relationKeeper, execution, claimed(regroupPayload()), ownership);

		verify(executionProgressService).finishCommand(eq(ownership), eq(ExecutionStatus.FINISHED_WITH_ERRORS), any(),
				any());
	}

	/**
	 * A medium that keeps no relations has nothing to regroup from. It is told so,
	 * rather than quietly given a full comparison - the two differ by minutes, and
	 * whoever asked would have no way of telling which one they got.
	 */
	@Test
	void aRegroupOverAMediumThatKeepsNoRelationsIsRejected() {
		job.run(analyzer, execution, claimed(regroupPayload()), ownership);

		verify(analyzer, never()).analyze(anyInt(), any());
		verify(publisher, never()).build(any(), any());
		verify(executionProgressService).finishCommand(eq(ownership), eq(ExecutionStatus.REJECTED), any(), any());
	}

	/**
	 * An arrival compares what arrived and nothing else, and it is guarded by the
	 * same promotion a regroup is: it drew its conclusions on top of the answer
	 * that was current when it started, and so may replace only that one.
	 */
	@Test
	void anAddIncorporatesTheArrivalsAndPublishesOverTheAnswerItStartedFrom() {
		when(publisher.currentAnswer(any())).thenReturn(11L);

		InOrder order = inOrder(publisher, relationKeeper);

		job.run(relationKeeper, execution, claimed(addPayload()), ownership);

		order.verify(publisher).currentAnswer(any());
		order.verify(adder()).add(anyInt(), any());
		order.verify(publisher).build(any(), any());

		verify(adder()).add(eq(70), any());
		verify(relationKeeper, never()).analyze(anyInt(), any());
		verify(regrouper(), never()).regroup(anyInt(), any());

		verify(publisher).publishIfStillBasedOn(any(), eq(11L), any());
		verify(publisher, never()).publish(any(), any());
		verify(executionProgressService).finishCommand(eq(ownership), eq(ExecutionStatus.FINISHED), any(), any());
	}

	/**
	 * And when a rebuild published while the arrival was working, the arrival's
	 * grouping is discarded rather than put over it. What it computed is not lost
	 * - the relations it approved are facts about pairs of images, and the winner
	 * reads the same table - but the answer it drew about a moment that has passed
	 * does not become the answer.
	 */
	@Test
	void anAddWhoseBaseWasReplacedDoesNotBecomeTheAnswer() {
		when(publisher.publishIfStillBasedOn(any(), any(), any())).thenReturn(false);

		job.run(relationKeeper, execution, claimed(addPayload()), ownership);

		verify(executionProgressService).finishCommand(eq(ownership), eq(ExecutionStatus.FINISHED_WITH_ERRORS), any(),
				any());
	}

	/**
	 * A medium that keeps no relations has nothing to add to. Told so, for the
	 * same reason a regroup is: a silent full comparison costs minutes and whoever
	 * asked would have no way of telling which one they got.
	 */
	@Test
	void anAddOverAMediumThatKeepsNoRelationsIsRejected() {
		job.run(analyzer, execution, claimed(addPayload()), ownership);

		verify(analyzer, never()).analyze(anyInt(), any());
		verify(publisher, never()).build(any(), any());
		verify(executionProgressService).finishCommand(eq(ownership), eq(ExecutionStatus.REJECTED), any(), any());
	}

	/**
	 * Cancelled while the arrival is comparing. Nothing is written down as an
	 * answer and the previous one is untouched - the grouping is built after the
	 * analysis returns, and the promotion is the only thing that retires anything.
	 *
	 * <p>
	 * The relations it had already approved stay, and that is deliberate rather
	 * than an oversight: they are facts about pairs of images, true whether or not
	 * anybody published a grouping, and each one is written in the same
	 * transaction as the coverage that accounts for it. A cancel therefore costs
	 * the answer and keeps the work.
	 */
	@Test
	void stopsPartWayThroughAnAddWithoutRetiringThePreviousAnswer() {
		when(adder().add(anyInt(), any())).thenAnswer(call -> {
			SimilarityProgressCallback progress = call.getArgument(1);

			progress.update(1, 40);

			when(executionCancellationService.isCancelled(42L)).thenReturn(true);

			progress.update(2, 40);

			return result(COMPOSITION);
		});

		job.run(relationKeeper, execution, claimed(addPayload()), ownership);

		verify(publisher, never()).build(any(), any());
		verify(publisher, never()).publishIfStillBasedOn(any(), any(), any());
		verify(executionProgressService).finishCommand(eq(ownership), eq(ExecutionStatus.CANCELLED), any(), any());
	}

	/**
	 * Cancelled after the arrival returned but before anything was published. The
	 * last checkpoint, and the one that decides whether anybody notices.
	 */
	@Test
	void stopsBetweenAnAddAndItsPublicationWithoutRetiringThePreviousAnswer() {
		when(adder().add(anyInt(), any())).thenAnswer(_ -> {
			when(executionCancellationService.isCancelled(42L)).thenReturn(true);

			return result(COMPOSITION);
		});

		job.run(relationKeeper, execution, claimed(addPayload()), ownership);

		verify(publisher, never()).build(any(), any());
		verify(publisher, never()).publishIfStillBasedOn(any(), any(), any());
		verify(executionProgressService).finishCommand(eq(ownership), eq(ExecutionStatus.CANCELLED), any(), any());
	}

	/**
	 * An arrival whose definition moved is refused like any other. The threshold
	 * or an exclusion changed after it was queued, so what it would incorporate is
	 * not what was asked for.
	 */
	@Test
	void anAddWhoseDefinitionChangedIsRejected() {
		String payload = executionPayloadCodec.encode(new SimilarityAnalysisPayload(
				DuplicateConstants.SIMILARITY_PAYLOAD_SCHEMA_VERSION, 70, "stale".repeat(12) + "0000", null,
				SimilarityRunMode.ADD));

		job.run(relationKeeper, execution, claimed(payload), ownership);

		verify(adder(), never()).add(anyInt(), any());
		verify(publisher, never()).build(any(), any());
		verify(executionProgressService).finishCommand(eq(ownership), eq(ExecutionStatus.REJECTED), any(), any());
	}

	/**
	 * A request written before the mode existed still runs, and runs as what it
	 * meant: every analysis queued until now was a full rebuild.
	 */
	@Test
	void aRequestThatNamesNoModeIsARebuild() {
		String payload = executionPayloadCodec.encode(new SimilarityAnalysisPayload(
				DuplicateConstants.SIMILARITY_PAYLOAD_SCHEMA_VERSION, 70, PARAMETERS, COMPOSITION, null));

		job.run(relationKeeper, execution, claimed(payload), ownership);

		verify(relationKeeper).analyze(eq(70), any());
		verify(regrouper(), never()).regroup(anyInt(), any());

		verify(publisher).publish(any(), any());
		verify(publisher, never()).currentAnswer(any());
	}

	/**
	 * Asked to stop before the analysis begins. Nothing is analysed, nothing is
	 * written, and the row says cancelled rather than failed - a person who pressed
	 * a button did not cause an error.
	 */
	@Test
	void stopsBeforeAnalysingAnythingWhenItWasAlreadyCancelled() {
		when(executionCancellationService.isCancelled(42L)).thenReturn(true);

		job.run(analyzer, execution, claimed(validPayload()), ownership);

		verify(analyzer, never()).analyze(anyInt(), any());
		verify(publisher, never()).build(any(), any());
		verify(publisher, never()).publish(any(), any());
		verify(executionProgressService).finishCommand(eq(ownership), eq(ExecutionStatus.CANCELLED), any(), any());
	}

	/**
	 * Asked to stop while the clustering runs. The checkpoint is the progress
	 * callback, which is called once per candidate - so the analysis unwinds at the
	 * end of the candidate it was on, and what it had computed is discarded rather
	 * than written down as an answer.
	 */
	@Test
	void stopsPartWayThroughTheAnalysisAndPublishesNothing() {
		when(analyzer.analyze(anyInt(), any())).thenAnswer(call -> {
			SimilarityProgressCallback progress = call.getArgument(1);

			progress.update(1, 500);

			when(executionCancellationService.isCancelled(42L)).thenReturn(true);

			progress.update(2, 500);

			return result(COMPOSITION);
		});

		job.run(analyzer, execution, claimed(validPayload()), ownership);

		verify(publisher, never()).build(any(), any());
		verify(publisher, never()).publish(any(), any());
		verify(executionProgressService).finishCommand(eq(ownership), eq(ExecutionStatus.CANCELLED), any(), any());
	}

	/**
	 * The last look, and the one that decides whether anybody notices. A cancel
	 * arriving after the analysis returned still costs nothing: the grouping is
	 * written by the line after this and the previous answer is retired by the line
	 * after that, so neither happens.
	 */
	@Test
	void stopsBetweenTheAnalysisAndThePublicationWithoutRetiringThePreviousAnswer() {
		when(analyzer.analyze(anyInt(), any())).thenAnswer(_ -> {
			when(executionCancellationService.isCancelled(42L)).thenReturn(true);

			return result(COMPOSITION);
		});

		job.run(analyzer, execution, claimed(validPayload()), ownership);

		verify(publisher, never()).build(any(), any());
		verify(publisher, never()).publish(any(), any());
		verify(executionProgressService).finishCommand(eq(ownership), eq(ExecutionStatus.CANCELLED), any(), any());
	}

	private String validPayload() {
		return executionPayloadCodec.encode(new SimilarityAnalysisPayload(
				DuplicateConstants.SIMILARITY_PAYLOAD_SCHEMA_VERSION, 70, PARAMETERS, COMPOSITION,
				SimilarityRunMode.REBUILD));
	}

	private String regroupPayload() {
		return executionPayloadCodec.encode(new SimilarityAnalysisPayload(
				DuplicateConstants.SIMILARITY_PAYLOAD_SCHEMA_VERSION, 70, PARAMETERS, COMPOSITION,
				SimilarityRunMode.REGROUP));
	}

	/**
	 * An arrival names no composition, and that absence is the request rather than
	 * an omission: what it was asked about is the difference, so there is no
	 * snapshot for it to have moved away from.
	 */
	private String addPayload() {
		return executionPayloadCodec.encode(new SimilarityAnalysisPayload(
				DuplicateConstants.SIMILARITY_PAYLOAD_SCHEMA_VERSION, 70, PARAMETERS, null, SimilarityRunMode.ADD));
	}

	private SimilarityRegrouper regrouper() {
		return (SimilarityRegrouper) relationKeeper;
	}

	private SimilarityAdder adder() {
		return (SimilarityAdder) relationKeeper;
	}

	private ClaimedExecution claimed(String payload) {
		return new ClaimedExecution(42L, ExecutionType.SIMILARITY_PHOTO.name(), null, null, payload);
	}

	private SimilarityAnalysisResult result(String compositionDigest) {
		return new SimilarityAnalysisResult(
				new SimilarityFamily(FileType.PHOTO, FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1,
						SimilarityConstants.GROUPING_VERSION, PARAMETERS),
				new SimilarityComposition(compositionDigest, 120, 118, 8000,
						SimilarityConstants.SELECTION_OLDEST_ELIGIBLE_FIRST),
				List.of(new AnalyzedGroup(96, 2048L, List.of(
						new AnalyzedMember(UUID.randomUUID(), Verdict.KEEP, Reason.ORIGINAL),
						new AnalyzedMember(UUID.randomUUID(), Verdict.DELETE_CANDIDATE, Reason.IDENTICAL_COPY)))));
	}

	private SimilarityGrouping grouping() {
		return SimilarityGrouping.builder().id(1L).similarityGroupingPublicId(UUID.randomUUID()).mediaType(FileType.PHOTO)
				.status(GroupingStatus.BUILDING).build();
	}
}