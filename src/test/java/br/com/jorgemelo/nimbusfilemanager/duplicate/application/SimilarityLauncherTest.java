package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.FingerprintAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.SimilarityConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityAnalysisPayload;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityComposition;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityFamily;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionEnqueueService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionMessageCodec;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionPayloadCodec;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * What the launcher writes into the queue is a contract with the worker, and
 * with the deduplication index. Two things have to hold: the dedup key must name
 * the exact analysis being asked for - so an identical request collapses onto
 * the row already queued, and a different one does not - and the payload must
 * carry what the request assumed, so the worker can tell that the world moved.
 */
class SimilarityLauncherTest {

	private static final String PHOTO_PARAMETERS = "p".repeat(64);
	private static final String COMPOSITION = "c".repeat(64);

	private final PhotoSimilarityService photoSimilarityService = mock(PhotoSimilarityService.class);
	private final VideoSimilarityService videoSimilarityService = mock(VideoSimilarityService.class);
	private final ExecutionEnqueueService executionEnqueueService = mock(ExecutionEnqueueService.class);
	private final ExecutionPayloadCodec executionPayloadCodec = new ExecutionPayloadCodec(new ObjectMapper());
	private final ExecutionMessageCodec executionMessageCodec = new ExecutionMessageCodec(new ObjectMapper());

	private final SimilarityLauncher launcher = new SimilarityLauncher(photoSimilarityService, videoSimilarityService,
			executionEnqueueService, executionPayloadCodec, executionMessageCodec);

	@BeforeEach
	void defaults() {
		when(photoSimilarityService.family(70)).thenReturn(new SimilarityFamily(FileType.PHOTO,
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1, SimilarityConstants.GROUPING_VERSION,
				PHOTO_PARAMETERS));
		when(photoSimilarityService.composition()).thenReturn(
				new SimilarityComposition(COMPOSITION, 120, 118, 8000, SimilarityConstants.SELECTION_OLDEST_FIRST));
		when(executionEnqueueService.enqueueOrExisting(any())).thenAnswer(call -> call.getArgument(0));
	}

	@Test
	void theDedupKeyNamesTheFamilyAndTheExactSetOfFiles() {
		Execution execution = launcher.launchPhotos(70);

		Assertions.assertThat(execution.getExecutionType()).isEqualTo(ExecutionType.SIMILARITY_PHOTO);
		Assertions.assertThat(execution.getDedupKey())
				.isEqualTo("PHOTO:" + FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1 + ':'
						+ SimilarityConstants.GROUPING_VERSION + ':' + PHOTO_PARAMETERS + ':' + COMPOSITION);
	}

	@Test
	void aRequestOverADifferentSetOfFilesIsADifferentRequest() {
		String first = launcher.launchPhotos(70).getDedupKey();

		when(photoSimilarityService.composition()).thenReturn(new SimilarityComposition("d".repeat(64), 121, 119, 8000,
				SimilarityConstants.SELECTION_OLDEST_FIRST));

		Assertions.assertThat(launcher.launchPhotos(70).getDedupKey()).isNotEqualTo(first);
	}

	@Test
	void thePayloadCarriesWhatTheRequestAssumedSoTheWorkerCanNoticeItChanged() {
		ArgumentCaptor<Execution> enqueued = ArgumentCaptor.forClass(Execution.class);

		launcher.launchPhotos(70);

		verify(executionEnqueueService).enqueueOrExisting(enqueued.capture());

		SimilarityAnalysisPayload payload = executionPayloadCodec.decode(enqueued.getValue().getRequestPayload(),
				SimilarityAnalysisPayload.class);

		Assertions.assertThat(payload.schemaVersion())
				.isEqualTo(DuplicateConstants.SIMILARITY_PAYLOAD_SCHEMA_VERSION);
		Assertions.assertThat(payload.minSimilarityPercent()).isEqualTo(70);
		Assertions.assertThat(payload.expectedParametersDigest()).isEqualTo(PHOTO_PARAMETERS);
		Assertions.assertThat(payload.expectedCompositionDigest()).isEqualTo(COMPOSITION);
	}

	@Test
	void aThresholdOutsideTheAllowedRangeIsClampedBeforeItReachesTheQueue() {
		when(photoSimilarityService.family(SimilarityBounds.clamp(5))).thenReturn(new SimilarityFamily(FileType.PHOTO,
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1, SimilarityConstants.GROUPING_VERSION,
				PHOTO_PARAMETERS));

		ArgumentCaptor<Execution> enqueued = ArgumentCaptor.forClass(Execution.class);

		launcher.launchPhotos(5);

		verify(executionEnqueueService).enqueueOrExisting(enqueued.capture());

		SimilarityAnalysisPayload payload = executionPayloadCodec.decode(enqueued.getValue().getRequestPayload(),
				SimilarityAnalysisPayload.class);

		Assertions.assertThat(payload.minSimilarityPercent()).isEqualTo(SimilarityBounds.clamp(5));
	}

	@Test
	void theVideosRequestGoesToTheVideoQueueWithTheVideoAnalyzersFamily() {
		when(videoSimilarityService.family(70)).thenReturn(new SimilarityFamily(FileType.VIDEO,
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1, SimilarityConstants.GROUPING_VERSION,
				"v".repeat(64)));
		when(videoSimilarityService.composition()).thenReturn(
				new SimilarityComposition(COMPOSITION, 9, 9, 2000, SimilarityConstants.SELECTION_OLDEST_FIRST));

		Execution execution = launcher.launchVideos(70);

		Assertions.assertThat(execution.getExecutionType()).isEqualTo(ExecutionType.SIMILARITY_VIDEO);
		Assertions.assertThat(execution.getDedupKey()).startsWith("VIDEO:");
		Assertions.assertThat(execution.getFilesFound()).isEqualTo(9);
	}
}