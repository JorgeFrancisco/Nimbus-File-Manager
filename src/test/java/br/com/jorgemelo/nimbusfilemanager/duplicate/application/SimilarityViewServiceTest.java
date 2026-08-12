package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.FingerprintAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.SimilarityConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.PublishedGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityComposition;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityFamily;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityView;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.GroupingStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.model.SimilarityGrouping;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.ExecutionRepository;

/**
 * The four states a screen can be in, and the rule that decides each one.
 *
 * <p>
 * The service never runs an analysis and never sees a grouping that is still
 * BUILDING - it asks the reader for what is ACTIVE. A recomputation in flight
 * therefore shows the previous answer and an "analysing" flag at the same time,
 * which is the behaviour these tests exist to hold.
 */
class SimilarityViewServiceTest {

	private static final String CURRENT_COMPOSITION = "c".repeat(64);
	private static final String PARAMETERS = "p".repeat(64);

	private final PhotoSimilarityService photoSimilarityService = mock(PhotoSimilarityService.class);
	private final VideoSimilarityService videoSimilarityService = mock(VideoSimilarityService.class);
	private final SimilarityResultReader reader = mock(SimilarityResultReader.class);
	private final ExecutionRepository executionRepository = mock(ExecutionRepository.class);

	private final SimilarityViewService service = new SimilarityViewService(photoSimilarityService,
			videoSimilarityService, reader, executionRepository);

	@Test
	void aLibraryThatWasNeverAnalysedIsNotPublishedAndOffersItsEligibleCount() {
		photoAnalyzer(CURRENT_COMPOSITION);
		when(reader.active(any())).thenReturn(Optional.empty());

		SimilarityView view = service.photos(70, PageRequest.of(0, 20));

		Assertions.assertThat(view.published()).isFalse();
		Assertions.assertThat(view.analyzing()).isFalse();
		Assertions.assertThat(view.groups()).isEmpty();
		Assertions.assertThat(view.eligibleCount()).isEqualTo(120);
		Assertions.assertThat(view.analyzedCount()).isZero();
		Assertions.assertThat(view.candidateLimit()).as("no medium caps any more")
				.isEqualTo(SimilarityConstants.NO_CANDIDATE_LIMIT);

		verify(reader, never()).page(any(), any());
	}

	@Test
	void aQueuedAnalysisShowsAsAnalysingBeforeAnythingIsPublished() {
		photoAnalyzer(CURRENT_COMPOSITION);
		when(reader.active(any())).thenReturn(Optional.empty());
		when(executionRepository.existsByExecutionTypeAndStatusIn(eq(ExecutionType.SIMILARITY_PHOTO),
				any())).thenReturn(true);

		SimilarityView view = service.photos(70, PageRequest.of(0, 20));

		Assertions.assertThat(view.published()).isFalse();
		Assertions.assertThat(view.analyzing()).isTrue();
	}

	@Test
	void anAnalysisOfTheCurrentLibraryIsPublishedAndNotOutdated() {
		photoAnalyzer(CURRENT_COMPOSITION);
		when(reader.active(any())).thenReturn(Optional.of(grouping(CURRENT_COMPOSITION, 120, 118)));
		when(reader.page(any(), any())).thenReturn(oneGroup());

		SimilarityView view = service.photos(70, PageRequest.of(0, 20));

		Assertions.assertThat(view.published()).isTrue();
		Assertions.assertThat(service.outdated(ExecutionType.SIMILARITY_PHOTO, 70)).contains(false);
		Assertions.assertThat(view.groups()).hasSize(1);
		Assertions.assertThat(view.eligibleCount()).isEqualTo(120);
		Assertions.assertThat(view.analyzedCount()).isEqualTo(118);
		Assertions.assertThat(view.coverageComplete()).isFalse();
	}

	/**
	 * The contract this screen's speed rests on. Identifying the whole eligible
	 * library to compare one digest was 2,5 s of the 2,9 s a navigation cost, and
	 * the page has nothing on it that depends on the answer - so building the view
	 * must not ask for it. It is asked for separately, by whoever needs it.
	 */
	@Test
	void buildingTheViewNeverIdentifiesTheWholeLibrary() {
		when(reader.active(any())).thenReturn(Optional.of(grouping(CURRENT_COMPOSITION, 120, 118)));
		when(reader.page(any(), any())).thenReturn(oneGroup());

		service.photos(70, PageRequest.of(0, 20));
		service.videos(70, PageRequest.of(0, 20));

		verify(photoSimilarityService, never()).composition();
		verify(videoSimilarityService, never()).composition();
	}

	/** Nothing published is not "current": there is no answer to be current. */
	@Test
	void aFamilyThatWasNeverAnalysedHasNoVerdictAtAll() {
		when(reader.active(any())).thenReturn(Optional.empty());

		Assertions.assertThat(service.outdated(ExecutionType.SIMILARITY_PHOTO, 70)).isEmpty();
		Assertions.assertThat(service.outdated(ExecutionType.SIMILARITY_VIDEO, 70)).isEmpty();
	}

	/** The same rule, asked about the other medium. */
	@Test
	void theVideoFamilyAnswersWithItsOwnComposition() {
		videoAnalyzer(CURRENT_COMPOSITION);
		when(reader.active(any())).thenReturn(Optional.of(grouping("d".repeat(64), 4, 4)));

		Assertions.assertThat(service.outdated(ExecutionType.SIMILARITY_VIDEO, 70)).contains(true);
	}

	@Test
	void anAnalysisOfADifferentSetOfFilesStaysVisibleButReportsItselfOutdated() {
		photoAnalyzer(CURRENT_COMPOSITION);
		when(reader.active(any())).thenReturn(Optional.of(grouping("d".repeat(64), 120, 120)));
		when(reader.page(any(), any())).thenReturn(oneGroup());

		SimilarityView view = service.photos(70, PageRequest.of(0, 20));

		Assertions.assertThat(view.published()).isTrue();
		Assertions.assertThat(service.outdated(ExecutionType.SIMILARITY_PHOTO, 70)).contains(true);
		Assertions.assertThat(view.groups()).hasSize(1);
		Assertions.assertThat(view.coverageComplete()).isTrue();
	}

	@Test
	void aRecomputationInFlightKeepsThePreviousAnswerOnScreen() {
		photoAnalyzer(CURRENT_COMPOSITION);
		when(reader.active(any())).thenReturn(Optional.of(grouping("d".repeat(64), 120, 120)));
		when(reader.page(any(), any())).thenReturn(oneGroup());
		when(executionRepository.existsByExecutionTypeAndStatusIn(eq(ExecutionType.SIMILARITY_PHOTO),
				any())).thenReturn(true);

		SimilarityView view = service.photos(70, PageRequest.of(0, 20));

		Assertions.assertThat(view.analyzing()).isTrue();
		Assertions.assertThat(view.published()).isTrue();
		Assertions.assertThat(view.groups()).hasSize(1);
	}

	@Test
	void theVideosTabAsksTheVideoAnalyzerAndTheVideoQueue() {
		when(videoSimilarityService.family(70)).thenReturn(new SimilarityFamily(FileType.VIDEO,
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1, SimilarityConstants.GROUPING_VERSION, PARAMETERS));
		when(videoSimilarityService.eligibleCount()).thenReturn(9);
		when(reader.active(any())).thenReturn(Optional.empty());

		SimilarityView view = service.videos(70, PageRequest.of(0, 20));

		Assertions.assertThat(view.eligibleCount()).isEqualTo(9);
		Assertions.assertThat(view.candidateLimit()).isEqualTo(SimilarityConstants.NO_CANDIDATE_LIMIT);

		verify(executionRepository).existsByExecutionTypeAndStatusIn(ExecutionType.SIMILARITY_VIDEO,
				List.of(ExecutionStatus.PENDING, ExecutionStatus.RUNNING));
		verify(photoSimilarityService, never()).family(anyInt());
	}

	@Test
	void everySimilarityExecutionTypeMapsToItsMediaType() {
		Assertions.assertThat(service.mediaTypeOf(ExecutionType.SIMILARITY_PHOTO)).isEqualTo(FileType.PHOTO);
		Assertions.assertThat(service.mediaTypeOf(ExecutionType.SIMILARITY_VIDEO)).isEqualTo(FileType.VIDEO);
	}

	private void photoAnalyzer(String compositionDigest) {
		when(photoSimilarityService.family(70)).thenReturn(new SimilarityFamily(FileType.PHOTO,
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1, SimilarityConstants.GROUPING_VERSION, PARAMETERS));
		when(photoSimilarityService.eligibleCount()).thenReturn(120);
		when(photoSimilarityService.composition()).thenReturn(new SimilarityComposition(compositionDigest, 120, 120,
				8000, SimilarityConstants.SELECTION_OLDEST_ELIGIBLE_FIRST));
	}

	private void videoAnalyzer(String compositionDigest) {
		when(videoSimilarityService.family(70)).thenReturn(new SimilarityFamily(FileType.VIDEO,
				FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1, SimilarityConstants.GROUPING_VERSION, PARAMETERS));
		when(videoSimilarityService.composition()).thenReturn(new SimilarityComposition(compositionDigest, 4, 4, 8000,
				SimilarityConstants.SELECTION_OLDEST_ELIGIBLE_FIRST));
	}

	private SimilarityGrouping grouping(String compositionDigest, int eligible, int analyzed) {
		return SimilarityGrouping.builder().id(1L).similarityGroupingPublicId(UUID.randomUUID()).mediaType(FileType.PHOTO)
				.algorithmId(FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1)
				.groupingVersion(SimilarityConstants.GROUPING_VERSION).parametersDigest(PARAMETERS)
				.compositionDigest(compositionDigest).eligibleCount(eligible).analyzedCount(analyzed)
				.candidateLimit(8000).selectionPolicy(SimilarityConstants.SELECTION_OLDEST_ELIGIBLE_FIRST)
				.status(GroupingStatus.ACTIVE).build();
	}

	private Page<PublishedGroup> oneGroup() {
		return new PageImpl<>(List.of(new PublishedGroup("7", 96, 2048L, List.of(), 0)));
	}
}