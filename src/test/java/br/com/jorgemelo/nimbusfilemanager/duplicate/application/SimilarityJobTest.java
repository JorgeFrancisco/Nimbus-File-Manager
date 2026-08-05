package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Verdict;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGrouping;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionCancellationService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionProgressService;
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

	private final ExecutionCancellationService executionCancellationService = mock(ExecutionCancellationService.class);

	private final SimilarityJob job = new SimilarityJob(publisher, executionProgressService,
			executionCancellationService, executionPayloadCodec);

	private final Execution execution = Execution.builder().id(42L).executionType(ExecutionType.SIMILARITY_PHOTO)
			.build();

	@BeforeEach
	void defaults() {
		when(analyzer.family(anyInt())).thenReturn(new SimilarityFamily(FileType.PHOTO,
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1, SimilarityConstants.GROUPING_VERSION, PARAMETERS));
		when(analyzer.eligibleCount()).thenReturn(120);
		when(analyzer.analyze(anyInt(), any())).thenReturn(result(COMPOSITION));
		when(publisher.build(any(), any())).thenReturn(grouping());
		when(publisher.publish(any())).thenReturn(true);
	}

	@Test
	void aPayloadWrittenByAnotherSchemaIsNotRun() {
		String payload = executionPayloadCodec.encode(new SimilarityAnalysisPayload(99, 70, PARAMETERS, COMPOSITION));

		ClaimedExecution claimed = claimed(payload);

		Assertions.assertThatThrownBy(() -> job.run(analyzer, execution, claimed))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schema");

		verify(analyzer, never()).analyze(anyInt(), any());
	}

	@Test
	void aRequestWhoseDefinitionChangedIsRejectedRatherThanAnsweredDifferently() {
		String payload = executionPayloadCodec.encode(new SimilarityAnalysisPayload(
				DuplicateConstants.SIMILARITY_PAYLOAD_SCHEMA_VERSION, 70, "stale".repeat(12) + "0000", COMPOSITION));

		job.run(analyzer, execution, claimed(payload));

		verify(executionProgressService).finishCommand(eq(execution), eq(ExecutionStatus.REJECTED), any(), any());
		verify(analyzer, never()).analyze(anyInt(), any());
		verify(publisher, never()).build(any(), any());
	}

	@Test
	void aSetOfFilesThatMovedIsStillPublished() {
		when(analyzer.analyze(anyInt(), any())).thenReturn(result("moved".repeat(12) + "0000"));

		String payload = executionPayloadCodec.encode(new SimilarityAnalysisPayload(
				DuplicateConstants.SIMILARITY_PAYLOAD_SCHEMA_VERSION, 70, PARAMETERS, COMPOSITION));

		job.run(analyzer, execution, claimed(payload));

		verify(publisher).build(any(), eq(42L));
		verify(publisher).publish(any());
		verify(executionProgressService).finishCommand(eq(execution), eq(ExecutionStatus.FINISHED), any(), any());
	}

	@Test
	void aPublicationThatLostTheRaceFinishesWithErrors() {
		when(publisher.publish(any())).thenReturn(false);

		String payload = executionPayloadCodec.encode(new SimilarityAnalysisPayload(
				DuplicateConstants.SIMILARITY_PAYLOAD_SCHEMA_VERSION, 70, PARAMETERS, COMPOSITION));

		job.run(analyzer, execution, claimed(payload));

		verify(executionProgressService).finishCommand(eq(execution), eq(ExecutionStatus.FINISHED_WITH_ERRORS), any(),
				any());
	}

	@Test
	void aPayloadWithoutAThresholdFallsBackToTheDocumentedDefault() {
		String payload = executionPayloadCodec.encode(
				new SimilarityAnalysisPayload(DuplicateConstants.SIMILARITY_PAYLOAD_SCHEMA_VERSION, null, null, null));

		job.run(analyzer, execution, claimed(payload));

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

		String payload = executionPayloadCodec.encode(new SimilarityAnalysisPayload(
				DuplicateConstants.SIMILARITY_PAYLOAD_SCHEMA_VERSION, 70, PARAMETERS, COMPOSITION));

		job.run(analyzer, execution, claimed(payload));

		verify(executionProgressService).updateLiveProgress(eq(execution), eq(118), eq(30), eq(0), eq(0), any());
	}

	/**
	 * Asked to stop before the analysis begins. Nothing is analysed, nothing is
	 * written, and the row says cancelled rather than failed - a person who pressed
	 * a button did not cause an error.
	 */
	@Test
	void stopsBeforeAnalysingAnythingWhenItWasAlreadyCancelled() {
		when(executionCancellationService.isCancelled(42L)).thenReturn(true);

		job.run(analyzer, execution, claimed(validPayload()));

		verify(analyzer, never()).analyze(anyInt(), any());
		verify(publisher, never()).build(any(), any());
		verify(publisher, never()).publish(any());
		verify(executionProgressService).finishCommand(eq(execution), eq(ExecutionStatus.CANCELLED), any(), any());
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

		job.run(analyzer, execution, claimed(validPayload()));

		verify(publisher, never()).build(any(), any());
		verify(publisher, never()).publish(any());
		verify(executionProgressService).finishCommand(eq(execution), eq(ExecutionStatus.CANCELLED), any(), any());
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

		job.run(analyzer, execution, claimed(validPayload()));

		verify(publisher, never()).build(any(), any());
		verify(publisher, never()).publish(any());
		verify(executionProgressService).finishCommand(eq(execution), eq(ExecutionStatus.CANCELLED), any(), any());
	}

	private String validPayload() {
		return executionPayloadCodec.encode(new SimilarityAnalysisPayload(
				DuplicateConstants.SIMILARITY_PAYLOAD_SCHEMA_VERSION, 70, PARAMETERS, COMPOSITION));
	}

	private ClaimedExecution claimed(String payload) {
		return new ClaimedExecution(42L, ExecutionType.SIMILARITY_PHOTO.name(), null, null, payload);
	}

	private SimilarityAnalysisResult result(String compositionDigest) {
		return new SimilarityAnalysisResult(
				new SimilarityFamily(FileType.PHOTO, FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1,
						SimilarityConstants.GROUPING_VERSION, PARAMETERS),
				new SimilarityComposition(compositionDigest, 120, 118, 8000,
						SimilarityConstants.SELECTION_OLDEST_FIRST),
				List.of(new AnalyzedGroup(96, 2048L, List.of(
						new AnalyzedMember(UUID.randomUUID(), Verdict.KEEP, Reason.ORIGINAL),
						new AnalyzedMember(UUID.randomUUID(), Verdict.DELETE_CANDIDATE, Reason.IDENTICAL_COPY)))));
	}

	private SimilarityGrouping grouping() {
		return SimilarityGrouping.builder().id(1L).publicId(UUID.randomUUID()).mediaType(FileType.PHOTO)
				.status(GroupingStatus.BUILDING).build();
	}
}