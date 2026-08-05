package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

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
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.SimilarityRunMode;
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
		when(photoSimilarityService.composition()).thenReturn(new SimilarityComposition(COMPOSITION, 120, 118, 8000,
				SimilarityConstants.SELECTION_OLDEST_ELIGIBLE_FIRST));
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
				SimilarityConstants.SELECTION_OLDEST_ELIGIBLE_FIRST));

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

	/**
	 * <b>The coalescing.</b> An arrival is deduplicated by the family alone, so a
	 * phone backup dropping photo after photo asks for the same work every time -
	 * and the queue answers with the request already waiting instead of adding
	 * another. What each of them wanted was "bring the answer up to date", and the
	 * one already queued will do exactly that, later, over more photos.
	 */
	@Test
	void everyArrivalAsksForTheSameWorkSoTheQueueCollapsesThemIntoOne() {
		String first = launcher.addPhotos(70).getDedupKey();

		// Three more photos arrive: a bigger library, a different composition.
		when(photoSimilarityService.composition()).thenReturn(new SimilarityComposition("d".repeat(64), 123, 121, 8000,
				SimilarityConstants.SELECTION_OLDEST_ELIGIBLE_FIRST));

		Assertions.assertThat(launcher.addPhotos(70).getDedupKey())
				.as("the same work, however many photos arrived meanwhile").isEqualTo(first);
	}

	/**
	 * And it is not the rebuild's work. A key that collided would answer a request
	 * for a full comparison with an arrival, or the reverse - the first silently
	 * skipping the comparison somebody asked for, the second analysing a snapshot
	 * nobody did.
	 */
	@Test
	void anArrivalIsNotTheSameWorkAsARebuild() {
		Assertions.assertThat(launcher.addPhotos(70).getDedupKey())
				.isNotEqualTo(launcher.launchPhotos(70).getDedupKey());
	}

	/**
	 * A different threshold is a different family and therefore different work:
	 * two published answers exist and both have to be brought up to date.
	 */
	@Test
	void anArrivalAtADifferentThresholdIsDifferentWork() {
		when(photoSimilarityService.family(95)).thenReturn(new SimilarityFamily(FileType.PHOTO,
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1, SimilarityConstants.GROUPING_VERSION,
				"q".repeat(64)));

		Assertions.assertThat(launcher.addPhotos(95).getDedupKey())
				.isNotEqualTo(launcher.addPhotos(70).getDedupKey());
	}

	/**
	 * The payload names the mode and no composition. The absence is the request:
	 * an arrival was asked about the difference, so there is no snapshot for it to
	 * have moved away from and nothing for the worker to report.
	 */
	@Test
	void anArrivalPayloadNamesTheModeAndNoComposition() {
		ArgumentCaptor<Execution> enqueued = ArgumentCaptor.forClass(Execution.class);

		launcher.addPhotos(70);

		verify(executionEnqueueService).enqueueOrExisting(enqueued.capture());

		SimilarityAnalysisPayload payload = executionPayloadCodec.decode(enqueued.getValue().getRequestPayload(),
				SimilarityAnalysisPayload.class);

		Assertions.assertThat(payload.mode()).isEqualTo(SimilarityRunMode.ADD);
		Assertions.assertThat(payload.expectedParametersDigest()).as("the definition is still checked")
				.isEqualTo(PHOTO_PARAMETERS);
		Assertions.assertThat(payload.expectedCompositionDigest()).isNull();
	}

	/**
	 * One request per analysed threshold when photos arrive - and none at all
	 * before the first analysis, because there is no answer yet for an arrival to
	 * bring up to date.
	 */
	@Test
	void anArrivalRefreshesEveryThresholdSomebodyHasAnalysedAndNothingBeforeTheFirst() {
		launcher.refreshPhotosAfterArrival();

		verify(executionEnqueueService, never()).enqueueOrExisting(any());

		when(photoSimilarityService.analysedThresholds()).thenReturn(List.of(70, 95));
		when(photoSimilarityService.family(95)).thenReturn(new SimilarityFamily(FileType.PHOTO,
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1, SimilarityConstants.GROUPING_VERSION,
				"q".repeat(64)));

		launcher.refreshPhotosAfterArrival();

		verify(executionEnqueueService, times(2)).enqueueOrExisting(any());
	}

	/**
	 * The same for videos, now that they keep relations and coverage too: a drone
	 * card draining into the backlog brings every published answer up to date, and
	 * a library being filled for the first time queues nothing.
	 */
	@Test
	void anArrivingVideoRefreshesEveryThresholdSomebodyHasAnalysed() {
		launcher.refreshVideosAfterArrival();

		verify(executionEnqueueService, never()).enqueueOrExisting(any());

		when(videoSimilarityService.analysedThresholds()).thenReturn(List.of(70, 95));
		when(videoSimilarityService.composition())
				.thenReturn(new SimilarityComposition(COMPOSITION, 9, 9, 2000,
						SimilarityConstants.SELECTION_OLDEST_ELIGIBLE_FIRST));
		when(videoSimilarityService.family(70)).thenReturn(new SimilarityFamily(FileType.VIDEO,
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_FRAMES_V1, SimilarityConstants.GROUPING_VERSION,
				"v".repeat(64)));
		when(videoSimilarityService.family(95)).thenReturn(new SimilarityFamily(FileType.VIDEO,
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_FRAMES_V1, SimilarityConstants.GROUPING_VERSION,
				"w".repeat(64)));

		launcher.refreshVideosAfterArrival();

		verify(executionEnqueueService, times(2)).enqueueOrExisting(any());
	}

	/**
	 * One change in who may be analysed, both media. A quarantine batch does not
	 * know whether what it moved was a photo or a video and must not have to: the
	 * launcher asks each analyser what it has already answered, and brings exactly
	 * those up to date.
	 */
	@Test
	void anEligibilityChangeRegroupsEveryAnalysedFamilyOfBothMedia() {
		launcher.refreshAfterEligibilityChange();

		verify(executionEnqueueService, never()).enqueueOrExisting(any());

		when(photoSimilarityService.analysedThresholds()).thenReturn(List.of(70));
		when(videoSimilarityService.analysedThresholds()).thenReturn(List.of(70));
		when(videoSimilarityService.family(70)).thenReturn(new SimilarityFamily(FileType.VIDEO,
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_FRAMES_V1, SimilarityConstants.GROUPING_VERSION,
				"v".repeat(64)));
		when(videoSimilarityService.composition()).thenReturn(new SimilarityComposition(COMPOSITION, 9, 9, 2000,
				SimilarityConstants.SELECTION_OLDEST_ELIGIBLE_FIRST));

		launcher.refreshAfterEligibilityChange();

		ArgumentCaptor<Execution> queued = ArgumentCaptor.forClass(Execution.class);

		verify(executionEnqueueService, times(2)).enqueueOrExisting(queued.capture());

		Assertions.assertThat(queued.getAllValues()).extracting(Execution::getDedupKey)
				.containsExactly("PHOTO:" + FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1 + ':'
						+ SimilarityConstants.GROUPING_VERSION + ':' + PHOTO_PARAMETERS + ":REGROUP",
						"VIDEO:" + FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_FRAMES_V1 + ':'
								+ SimilarityConstants.GROUPING_VERSION + ':' + "v".repeat(64) + ":REGROUP");

		Assertions.assertThat(queued.getAllValues()).extracting(this::modeOf)
				.containsOnly(SimilarityRunMode.REGROUP);
	}

	/**
	 * The three keys have to stay apart. A rebuild names the snapshot it was asked
	 * about, an arrival and a regroup name the mode - so a click on the button, a
	 * photo landing and a file being excluded are three pieces of work, and none of
	 * them is ever answered with another.
	 */
	@Test
	void aRebuildAnArrivalAndARegroupOverOneFamilyAreThreeDifferentRequests() {
		when(photoSimilarityService.analysedThresholds()).thenReturn(List.of(70));

		Execution rebuild = launcher.launchPhotos(70);
		Execution arrival = launcher.addPhotos(70);

		launcher.refreshAfterEligibilityChange();

		ArgumentCaptor<Execution> queued = ArgumentCaptor.forClass(Execution.class);

		verify(executionEnqueueService, times(3)).enqueueOrExisting(queued.capture());

		Execution regroup = queued.getAllValues().get(2);

		Assertions.assertThat(List.of(rebuild.getDedupKey(), arrival.getDedupKey(), regroup.getDedupKey()))
				.doesNotHaveDuplicates();

		Assertions.assertThat(modeOf(rebuild)).isEqualTo(SimilarityRunMode.REBUILD);
		Assertions.assertThat(modeOf(arrival)).isEqualTo(SimilarityRunMode.ADD);
		Assertions.assertThat(modeOf(regroup)).isEqualTo(SimilarityRunMode.REGROUP);
	}

	/**
	 * A regroup is about who takes part when it starts, so - like an arrival, and
	 * unlike a rebuild - it names no composition. Carrying one would make the
	 * worker refuse work whose whole purpose is that the set changed.
	 */
	@Test
	void aRegroupPayloadNamesTheModeAndNoComposition() {
		when(photoSimilarityService.analysedThresholds()).thenReturn(List.of(70));

		launcher.refreshAfterEligibilityChange();

		ArgumentCaptor<Execution> queued = ArgumentCaptor.forClass(Execution.class);

		verify(executionEnqueueService).enqueueOrExisting(queued.capture());

		SimilarityAnalysisPayload payload = executionPayloadCodec.decode(queued.getValue().getRequestPayload(),
				SimilarityAnalysisPayload.class);

		Assertions.assertThat(payload.mode()).isEqualTo(SimilarityRunMode.REGROUP);
		Assertions.assertThat(payload.expectedCompositionDigest()).isNull();
		Assertions.assertThat(payload.expectedParametersDigest()).isEqualTo(PHOTO_PARAMETERS);
		Assertions.assertThat(payload.schemaVersion()).isEqualTo(DuplicateConstants.SIMILARITY_PAYLOAD_SCHEMA_VERSION);
	}

	private SimilarityRunMode modeOf(Execution execution) {
		return executionPayloadCodec.decode(execution.getRequestPayload(), SimilarityAnalysisPayload.class).mode();
	}

	@Test
	void theVideosRequestGoesToTheVideoQueueWithTheVideoAnalyzersFamily() {
		when(videoSimilarityService.family(70)).thenReturn(new SimilarityFamily(FileType.VIDEO,
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1, SimilarityConstants.GROUPING_VERSION,
				"v".repeat(64)));
		when(videoSimilarityService.composition()).thenReturn(new SimilarityComposition(COMPOSITION, 9, 9, 2000,
				SimilarityConstants.SELECTION_OLDEST_ELIGIBLE_FIRST));

		Execution execution = launcher.launchVideos(70);

		Assertions.assertThat(execution.getExecutionType()).isEqualTo(ExecutionType.SIMILARITY_VIDEO);
		Assertions.assertThat(execution.getDedupKey()).startsWith("VIDEO:");
		Assertions.assertThat(execution.getFilesFound()).isEqualTo(9);
	}
}