package br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.ui.ExtendedModelMap;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateDeletionAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateExclusionService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.PhotoSimilarityAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.PhotoSimilarityService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.VideoSimilarityAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.VideoSimilarityService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateCandidateFileResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateCandidateGroupResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateDeleteRequest;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateDeletionResult;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateFileView;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateGroupView;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicatesViewRequest;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarPhotoGroupResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarVideoGroupResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.PhashBacklogAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.PhashBacklogService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.VideoFingerprintBacklogAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.VideoFingerprintBacklogService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Reason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Verdict;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.SizeResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

class DuplicatesWebControllerTest {

	private static final LocalDateTime NOW = LocalDateTime.parse("2026-07-08T12:00:00");

	/**
	 * Collaborators bundled for the constructor. Every panel reads its counts from
	 * the runner's live status, so a runner left answering null leaves the screen
	 * with nothing to render - which is how fourteen of these tests failed at once.
	 */
	private static PhashBacklogAsyncRunner phashRunner() {
		PhashBacklogAsyncRunner runner = mock(PhashBacklogAsyncRunner.class);

		when(runner.liveStatus()).thenReturn(new FingerprintBacklogStatus(0, 0, 0));

		return runner;
	}

	private static VideoFingerprintBacklogAsyncRunner videoBacklogRunner() {
		VideoFingerprintBacklogAsyncRunner runner = mock(VideoFingerprintBacklogAsyncRunner.class);

		when(runner.liveStatus()).thenReturn(new FingerprintBacklogStatus(0, 0, 0));

		return runner;
	}

	private static VideoSimilarityWeb videoWeb() {
		return new VideoSimilarityWeb(mock(VideoSimilarityService.class), mock(VideoSimilarityAsyncRunner.class),
				mock(VideoFingerprintBacklogService.class), videoBacklogRunner());
	}

	@Test
	void videosTabRendersTheVideoSimilarityGroupingWithVideoActions() {
		DuplicateService duplicateService = mock(DuplicateService.class);
		PhashBacklogService phashBacklogService = mock(PhashBacklogService.class);
		UserPagePreferenceService preferences = mock(UserPagePreferenceService.class);
		VideoSimilarityService videoSimilarity = mock(VideoSimilarityService.class);
		VideoFingerprintBacklogService videoBacklog = mock(VideoFingerprintBacklogService.class);

		when(preferences.find(any(), eq(DuplicateConstants.PAGE_KEY))).thenReturn(Map.of());
		when(videoSimilarity.cachedPage(ArgumentMatchers.anyInt(), any()))
				.thenReturn(Optional.of(new PageImpl<>(List.of())));

		VideoFingerprintBacklogAsyncRunner videoBacklogRunner = mock(VideoFingerprintBacklogAsyncRunner.class);

		when(videoBacklogRunner.liveStatus()).thenReturn(new FingerprintBacklogStatus(0, 5, 0));

		VideoSimilarityWeb videoWeb = new VideoSimilarityWeb(videoSimilarity, mock(VideoSimilarityAsyncRunner.class),
				videoBacklog, videoBacklogRunner);

		ExtendedModelMap model = new ExtendedModelMap();

		String view = new DuplicatesWebController(duplicateService, mock(PhotoSimilarityService.class),
				phashBacklogService, phashRunner(), preferences,
				mock(PhotoSimilarityAsyncRunner.class), mock(DuplicateDeletionAsyncRunner.class),
				mock(DuplicateExclusionService.class), videoWeb)
				.duplicates(new DuplicatesViewRequest("videos", 0, 70, "details", null, null), null, model);

		Assertions.assertThat(view).isEqualTo("app/duplicates");
		Assertions.assertThat(model).containsEntry("activeTab", "videos")
				.containsEntry("rebuildAction", "/app/duplicates/phash-video/rebuild")
				.containsEntry("retryAction", "/app/duplicates/phash-video/retry");
	}

	/**
	 * The video groups get their own wording, separate from the photo tab's: a
	 * one-file group reads in the singular and anything larger in the plural, and
	 * the badge carries the similarity plus the recoverable size.
	 */
	@Test
	void videosTabRendersGroupHeadersInSingularAndPluralWithTheirOwnWording() {
		UserPagePreferenceService preferences = mock(UserPagePreferenceService.class);
		VideoSimilarityService videoSimilarity = mock(VideoSimilarityService.class);
		VideoFingerprintBacklogService videoBacklog = mock(VideoFingerprintBacklogService.class);

		DuplicateCandidateFileResponse keep = new DuplicateCandidateFileResponse(1L, "keep.mp4", "mp4", "VIDEO",
				SizeResponse.of(100), "C:/keep.mp4", "C:/", NOW);
		DuplicateCandidateFileResponse candidate = new DuplicateCandidateFileResponse(2L, "dup.mp4", "mp4", "VIDEO",
				SizeResponse.of(100), "C:/dup.mp4", "C:/", NOW);

		var page = new PageImpl<>(
				List.of(new SimilarVideoGroupResponse("single", 1, 98, SizeResponse.of(0), keep, List.of()),
						new SimilarVideoGroupResponse("many", 3, 91, SizeResponse.of(200), keep, List.of(candidate))),
				PageRequest.of(0, 50), 2);

		when(preferences.find(any(), eq(DuplicateConstants.PAGE_KEY))).thenReturn(Map.of());
		when(videoSimilarity.cachedPage(ArgumentMatchers.anyInt(), any())).thenReturn(Optional.of(page));

		VideoFingerprintBacklogAsyncRunner videoBacklogRunner = mock(VideoFingerprintBacklogAsyncRunner.class);

		when(videoBacklogRunner.liveStatus()).thenReturn(new FingerprintBacklogStatus(0, 5, 0));

		VideoSimilarityWeb videoWeb = new VideoSimilarityWeb(videoSimilarity, mock(VideoSimilarityAsyncRunner.class),
				videoBacklog, videoBacklogRunner);

		ExtendedModelMap model = new ExtendedModelMap();

		new DuplicatesWebController(mock(DuplicateService.class), mock(PhotoSimilarityService.class),
				mock(PhashBacklogService.class), phashRunner(), preferences,
				mock(PhotoSimilarityAsyncRunner.class), mock(DuplicateDeletionAsyncRunner.class),
				mock(DuplicateExclusionService.class), videoWeb)
				.duplicates(new DuplicatesViewRequest("videos", 0, 70, "details", null, null), null, model);

		Assertions.assertThat(model.get("groups"))
				.asInstanceOf(InstanceOfAssertFactories.list(DuplicateGroupView.class))
				.satisfiesExactly(single -> {
					Assertions.assertThat(single.groupId()).isEqualTo("single");
					Assertions.assertThat(single.headerText()).contains("1");
				}, many -> {
					Assertions.assertThat(many.groupId()).isEqualTo("many");
					Assertions.assertThat(many.headerText()).contains("3");
					Assertions.assertThat(many.badgeText()).contains("91");
				});
	}

	@Test
	void videosTabReadsItsOwnSavedSimilarityIndependentlyOfPhotos() {
		UserPagePreferenceService preferences = mock(UserPagePreferenceService.class);
		VideoSimilarityService videoSimilarity = mock(VideoSimilarityService.class);
		VideoFingerprintBacklogService videoBacklog = mock(VideoFingerprintBacklogService.class);

		// Photo threshold 80, video threshold 95: the video tab must read its own key.
		when(preferences.find(any(), eq(DuplicateConstants.PAGE_KEY))).thenReturn(Map.of(
				DuplicateConstants.MIN_SIMILARITY_KEY, "80", DuplicateConstants.MIN_SIMILARITY_VIDEO_KEY, "95"));
		when(videoSimilarity.cachedPage(ArgumentMatchers.anyInt(), any()))
				.thenReturn(Optional.of(new PageImpl<>(List.of())));

		VideoFingerprintBacklogAsyncRunner videoBacklogRunner = mock(VideoFingerprintBacklogAsyncRunner.class);

		when(videoBacklogRunner.liveStatus()).thenReturn(new FingerprintBacklogStatus(0, 5, 0));

		VideoSimilarityWeb videoWeb = new VideoSimilarityWeb(videoSimilarity, mock(VideoSimilarityAsyncRunner.class),
				videoBacklog, videoBacklogRunner);

		ExtendedModelMap model = new ExtendedModelMap();

		new DuplicatesWebController(mock(DuplicateService.class), mock(PhotoSimilarityService.class),
				mock(PhashBacklogService.class), phashRunner(), preferences,
				mock(PhotoSimilarityAsyncRunner.class), mock(DuplicateDeletionAsyncRunner.class),
				mock(DuplicateExclusionService.class), videoWeb)
				.duplicates(new DuplicatesViewRequest("videos", 0, null, "details", null, null), null, model);

		Assertions.assertThat(model).containsEntry("minSimilarity", 95);
		verify(videoSimilarity).cachedPage(95, PageRequest.of(0, 50));
	}

	@Test
	void videosTabComputesSimilarityInBackgroundWhenNotCached() {
		UserPagePreferenceService preferences = mock(UserPagePreferenceService.class);
		VideoSimilarityService videoSimilarity = mock(VideoSimilarityService.class);
		VideoSimilarityAsyncRunner videoSimilarityRunner = mock(VideoSimilarityAsyncRunner.class);
		VideoFingerprintBacklogService videoBacklog = mock(VideoFingerprintBacklogService.class);

		when(preferences.find(any(), eq(DuplicateConstants.PAGE_KEY))).thenReturn(Map.of());
		when(videoSimilarity.cachedPage(ArgumentMatchers.anyInt(), any())).thenReturn(Optional.empty());
		when(videoSimilarityRunner.start(70)).thenReturn(true);
		when(videoSimilarityRunner.percent()).thenReturn(40);
		when(videoSimilarityRunner.processed()).thenReturn(2);
		when(videoSimilarityRunner.total()).thenReturn(5);

		VideoSimilarityWeb videoWeb = new VideoSimilarityWeb(videoSimilarity, videoSimilarityRunner, videoBacklog,
				videoBacklogRunner());

		ExtendedModelMap model = new ExtendedModelMap();

		new DuplicatesWebController(mock(DuplicateService.class), mock(PhotoSimilarityService.class),
				mock(PhashBacklogService.class), phashRunner(), preferences,
				mock(PhotoSimilarityAsyncRunner.class), mock(DuplicateDeletionAsyncRunner.class),
				mock(DuplicateExclusionService.class), videoWeb)
				.duplicates(new DuplicatesViewRequest("videos", 0, 70, "details", null, null), null, model);

		verify(videoSimilarityRunner).run(70);
		Assertions.assertThat(model).containsEntry("similarityComputing", true).containsEntry("similarityPercent", 40);
	}

	@Test
	void videosTabIsBlockedWhileVideoFingerprintsArePending() {
		UserPagePreferenceService preferences = mock(UserPagePreferenceService.class);
		VideoFingerprintBacklogService videoBacklog = mock(VideoFingerprintBacklogService.class);

		VideoFingerprintBacklogAsyncRunner videoBacklogRunner = mock(VideoFingerprintBacklogAsyncRunner.class);

		when(preferences.find(any(), eq(DuplicateConstants.PAGE_KEY))).thenReturn(Map.of());
		when(videoBacklogRunner.liveStatus()).thenReturn(new FingerprintBacklogStatus(3, 0, 0));

		VideoSimilarityWeb videoWeb = new VideoSimilarityWeb(mock(VideoSimilarityService.class),
				mock(VideoSimilarityAsyncRunner.class), videoBacklog, videoBacklogRunner);

		ExtendedModelMap model = new ExtendedModelMap();

		new DuplicatesWebController(mock(DuplicateService.class), mock(PhotoSimilarityService.class),
				mock(PhashBacklogService.class), phashRunner(), preferences,
				mock(PhotoSimilarityAsyncRunner.class), mock(DuplicateDeletionAsyncRunner.class),
				mock(DuplicateExclusionService.class), videoWeb)
				.duplicates(new DuplicatesViewRequest("videos", 0, 70, "details", null, null), null, model);

		Assertions.assertThat(model).containsEntry("phashBlocking", true);
	}

	@Test
	void retryVideoFingerprintsResetsFailuresAndRedirects() {
		VideoFingerprintBacklogService videoBacklog = mock(VideoFingerprintBacklogService.class);
		VideoFingerprintBacklogAsyncRunner videoRunner = mock(VideoFingerprintBacklogAsyncRunner.class);

		when(videoRunner.start()).thenReturn(true);

		VideoSimilarityWeb videoWeb = new VideoSimilarityWeb(mock(VideoSimilarityService.class),
				mock(VideoSimilarityAsyncRunner.class), videoBacklog, videoRunner);

		String redirect = controllerWith(videoWeb).retryVideoFingerprints();

		verify(videoBacklog).resetFailures();
		verify(videoRunner).run();
		Assertions.assertThat(redirect).isEqualTo("redirect:/app/duplicates?tab=videos");
	}

	@Test
	void rebuildVideoFingerprintsStartsTheTrackedVideoJob() {
		VideoFingerprintBacklogAsyncRunner videoRunner = mock(VideoFingerprintBacklogAsyncRunner.class);

		when(videoRunner.prepareRebuild()).thenReturn(true);

		VideoSimilarityWeb videoWeb = new VideoSimilarityWeb(mock(VideoSimilarityService.class),
				mock(VideoSimilarityAsyncRunner.class), mock(VideoFingerprintBacklogService.class), videoRunner);

		String redirect = controllerWith(videoWeb).rebuildVideoFingerprints();

		verify(videoRunner).run();
		Assertions.assertThat(redirect).isEqualTo("redirect:/app/duplicates?tab=videos");
	}

	private static DuplicatesWebController controllerWith(VideoSimilarityWeb videoWeb) {
		return new DuplicatesWebController(mock(DuplicateService.class), mock(PhotoSimilarityService.class),
				mock(PhashBacklogService.class), phashRunner(),
				mock(UserPagePreferenceService.class), mock(PhotoSimilarityAsyncRunner.class),
				mock(DuplicateDeletionAsyncRunner.class), mock(DuplicateExclusionService.class), videoWeb);
	}

	@Test
	void duplicatesShouldMapExactCandidatesAndFallBackToDetailsViewForInvalidValue() {
		DuplicateService duplicateService = mock(DuplicateService.class);
		PhotoSimilarityService photoSimilarityService = mock(PhotoSimilarityService.class);
		var phashBacklogService = mock(PhashBacklogService.class);
		var phashBacklogAsyncRunner = mock(PhashBacklogAsyncRunner.class);
		when(phashBacklogAsyncRunner.liveStatus()).thenReturn(new FingerprintBacklogStatus(0, 0, 0));
		ExtendedModelMap model = new ExtendedModelMap();
		DuplicateCandidateFileResponse keep = new DuplicateCandidateFileResponse(1L, "keep.jpg", "jpg", "PHOTO",
				SizeResponse.of(100), "C:/keep.jpg", "C:/", NOW);
		DuplicateCandidateFileResponse candidate = new DuplicateCandidateFileResponse(2L, "dup.jpg", "jpg", "PHOTO",
				SizeResponse.of(100), "C:/dup.jpg", "C:/", NOW);
		var page = new PageImpl<>(
				List.of(new DuplicateCandidateGroupResponse("sha", 2, SizeResponse.of(100), keep, List.of(candidate))),
				PageRequest.of(0, 20), 1);

		when(duplicateService.candidates(eq(PageRequest.of(0, 50)), any())).thenReturn(page);

		String view = new DuplicatesWebController(duplicateService, photoSimilarityService, phashBacklogService,
				phashBacklogAsyncRunner, mock(UserPagePreferenceService.class), mock(PhotoSimilarityAsyncRunner.class),
				mock(DuplicateDeletionAsyncRunner.class), mock(DuplicateExclusionService.class), videoWeb())
				.duplicates(new DuplicatesViewRequest("exact", 0, 70, "not-a-real-view", null, null), null, model);

		Assertions.assertThat(view).isEqualTo("app/duplicates");
		Assertions.assertThat(model).containsEntry("view", "details").containsEntry("activeTab", "exact");

		@SuppressWarnings("unchecked")
		List<DuplicateGroupView> groups = (List<DuplicateGroupView>) model.get("groups");

		Assertions.assertThat(groups).hasSize(1);
		Assertions.assertThat(groups.getFirst().groupId()).isEqualTo("sha");
		Assertions.assertThat(groups.getFirst().headerText()).isEqualTo("2 arquivos idênticos");
		Assertions.assertThat(groups.getFirst().files()).extracting("id").containsExactly(UuidV7.fromLegacy(1L),
				UuidV7.fromLegacy(2L));
		Assertions.assertThat(groups.getFirst().files().getFirst().keep()).isTrue();
		Assertions.assertThat(groups.getFirst().files().getLast().keep()).isFalse();
		Assertions.assertThat(groups.getFirst().files().getFirst().image()).isTrue();
		Assertions.assertThat(groups.getFirst().files().getFirst().previewUrl())
				.isEqualTo("/api/media/" + groups.getFirst().files().getFirst().id() + "/thumbnail?w=320");
	}

	@Test
	void duplicatesShouldClampMinSimilarityBelowFloorForSimilarTab() {
		DuplicateService duplicateService = mock(DuplicateService.class);
		PhotoSimilarityService photoSimilarityService = mock(PhotoSimilarityService.class);
		var phashBacklogService = mock(PhashBacklogService.class);
		var phashBacklogAsyncRunner = mock(PhashBacklogAsyncRunner.class);
		when(phashBacklogAsyncRunner.liveStatus()).thenReturn(new FingerprintBacklogStatus(0, 0, 0));
		ExtendedModelMap model = new ExtendedModelMap();
		DuplicateCandidateFileResponse keep = new DuplicateCandidateFileResponse(1L, "keep.jpg", "jpg", "PHOTO",
				SizeResponse.of(200), "C:/keep.jpg", "C:/", NOW);
		var page = new PageImpl<>(
				List.of(new SimilarPhotoGroupResponse("1", 1, 92, SizeResponse.of(0), keep, List.of())),
				PageRequest.of(0, 20), 1);

		when(photoSimilarityService.cachedPage(70, PageRequest.of(0, 50))).thenReturn(Optional.of(page));

		String view = new DuplicatesWebController(duplicateService, photoSimilarityService, phashBacklogService,
				phashBacklogAsyncRunner, mock(UserPagePreferenceService.class), mock(PhotoSimilarityAsyncRunner.class),
				mock(DuplicateDeletionAsyncRunner.class), mock(DuplicateExclusionService.class), videoWeb())
						.duplicates(new DuplicatesViewRequest("similar", 0, 10, "small", null, null), null, model);

		Assertions.assertThat(view).isEqualTo("app/duplicates");
		Assertions.assertThat(model).containsEntry("minSimilarity", 70).containsEntry("view", "small")
				.containsEntry("similarityComputing", false);

		@SuppressWarnings("unchecked")
		List<DuplicateGroupView> groups = (List<DuplicateGroupView>) model.get("groups");

		Assertions.assertThat(groups.getFirst().badgeText()).contains("92% de semelhança");
		verify(photoSimilarityService).cachedPage(70, PageRequest.of(0, 50));
	}

	@Test
	void duplicatesShouldComputeSimilarityInBackgroundWhenNotCached() {
		DuplicateService duplicateService = mock(DuplicateService.class);
		PhotoSimilarityService photoSimilarityService = mock(PhotoSimilarityService.class);
		var phashBacklogService = mock(PhashBacklogService.class);
		var phashBacklogAsyncRunner = mock(PhashBacklogAsyncRunner.class);
		when(phashBacklogAsyncRunner.liveStatus()).thenReturn(new FingerprintBacklogStatus(0, 0, 0));
		var similarityRunner = mock(PhotoSimilarityAsyncRunner.class);
		when(photoSimilarityService.cachedPage(70, PageRequest.of(0, 50))).thenReturn(Optional.empty());
		when(similarityRunner.start(70)).thenReturn(true);
		when(similarityRunner.percent()).thenReturn(42);
		when(similarityRunner.processed()).thenReturn(21);
		when(similarityRunner.total()).thenReturn(50);
		ExtendedModelMap model = new ExtendedModelMap();

		String view = new DuplicatesWebController(duplicateService, photoSimilarityService, phashBacklogService,
				phashBacklogAsyncRunner, mock(UserPagePreferenceService.class), similarityRunner,
				mock(DuplicateDeletionAsyncRunner.class), mock(DuplicateExclusionService.class), videoWeb())
						.duplicates(new DuplicatesViewRequest("similar", 0, 70, "details", null, null), null, model);

		Assertions.assertThat(view).isEqualTo("app/duplicates");
		Assertions.assertThat(model).containsEntry("similarityComputing", true).containsEntry("similarityPercent", 42)
				.containsEntry("similarityProcessed", 21).containsEntry("similarityTotal", 50);
		Assertions.assertThat((List<?>) model.get("groups")).isEmpty();
		verify(similarityRunner).start(70);
		verify(similarityRunner).run(70);
	}

	@Test
	void duplicatesShouldNotRestartBackgroundSimilarityWhenAlreadyRunning() {
		DuplicateService duplicateService = mock(DuplicateService.class);
		PhotoSimilarityService photoSimilarityService = mock(PhotoSimilarityService.class);
		var phashBacklogService = mock(PhashBacklogService.class);
		var phashBacklogAsyncRunner = mock(PhashBacklogAsyncRunner.class);
		when(phashBacklogAsyncRunner.liveStatus()).thenReturn(new FingerprintBacklogStatus(0, 0, 0));
		var similarityRunner = mock(PhotoSimilarityAsyncRunner.class);
		when(photoSimilarityService.cachedPage(70, PageRequest.of(0, 50))).thenReturn(Optional.empty());
		when(similarityRunner.start(70)).thenReturn(false);
		ExtendedModelMap model = new ExtendedModelMap();

		new DuplicatesWebController(duplicateService, photoSimilarityService, phashBacklogService,
				phashBacklogAsyncRunner, mock(UserPagePreferenceService.class), similarityRunner,
				mock(DuplicateDeletionAsyncRunner.class), mock(DuplicateExclusionService.class), videoWeb())
						.duplicates(new DuplicatesViewRequest("similar", 0, 70, "details", null, null), null, model);

		Assertions.assertThat(model).containsEntry("similarityComputing", true);
		verify(similarityRunner).start(70);
		verify(similarityRunner, never()).run(ArgumentMatchers.anyInt());
	}

	@Test
	void duplicatesShouldPersistTheRequestedTab() {
		DuplicateService duplicateService = mock(DuplicateService.class);
		var phashBacklogService = mock(PhashBacklogService.class);
		var phashBacklogAsyncRunner = mock(PhashBacklogAsyncRunner.class);
		when(phashBacklogAsyncRunner.liveStatus()).thenReturn(new FingerprintBacklogStatus(0, 0, 0));
		var preferences = mock(UserPagePreferenceService.class);
		when(preferences.find(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Map.of());
		when(duplicateService.candidates(eq(PageRequest.of(0, 50)), any())).thenReturn(new PageImpl<>(List.of()));
		ExtendedModelMap model = new ExtendedModelMap();

		new DuplicatesWebController(duplicateService, mock(PhotoSimilarityService.class), phashBacklogService,
				phashBacklogAsyncRunner, preferences, mock(PhotoSimilarityAsyncRunner.class),
				mock(DuplicateDeletionAsyncRunner.class), mock(DuplicateExclusionService.class), videoWeb())
						.duplicates(new DuplicatesViewRequest("exact", 0, 70, "details", null, null), null, model);

		Assertions.assertThat(model).containsEntry("activeTab", "exact");
		verify(preferences).save("system", DuplicateConstants.PAGE_KEY, DuplicateConstants.TAB_KEY, "exact");
	}

	@Test
	void duplicatesShouldFallBackToTheSavedTabWhenNoneIsRequested() {
		DuplicateService duplicateService = mock(DuplicateService.class);
		var phashBacklogService = mock(PhashBacklogService.class);
		var phashBacklogAsyncRunner = mock(PhashBacklogAsyncRunner.class);
		when(phashBacklogAsyncRunner.liveStatus()).thenReturn(new FingerprintBacklogStatus(0, 0, 0));
		var preferences = mock(UserPagePreferenceService.class);
		when(preferences.find(ArgumentMatchers.any(), ArgumentMatchers.any()))
				.thenReturn(Map.of(DuplicateConstants.TAB_KEY, "similar"));
		var photoSimilarityService = mock(PhotoSimilarityService.class);
		when(photoSimilarityService.cachedPage(ArgumentMatchers.anyInt(), ArgumentMatchers.any()))
				.thenReturn(Optional.of(new PageImpl<>(List.of())));
		ExtendedModelMap model = new ExtendedModelMap();

		new DuplicatesWebController(duplicateService, photoSimilarityService, phashBacklogService,
				phashBacklogAsyncRunner, preferences, mock(PhotoSimilarityAsyncRunner.class),
				mock(DuplicateDeletionAsyncRunner.class), mock(DuplicateExclusionService.class), videoWeb())
						.duplicates(new DuplicatesViewRequest(null, 0, 70, "details", null, null), null, model);

		Assertions.assertThat(model).containsEntry("activeTab", "similar");
	}

	@Test
	void duplicatesShouldPersistAndApplyTheRequestedPageSize() {
		DuplicateService duplicateService = mock(DuplicateService.class);
		var phashBacklogService = mock(PhashBacklogService.class);
		var phashBacklogAsyncRunner = mock(PhashBacklogAsyncRunner.class);
		when(phashBacklogAsyncRunner.liveStatus()).thenReturn(new FingerprintBacklogStatus(0, 0, 0));
		var preferences = mock(UserPagePreferenceService.class);
		when(preferences.find(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Map.of());
		when(duplicateService.candidates(eq(PageRequest.of(0, 100)), any())).thenReturn(new PageImpl<>(List.of()));
		ExtendedModelMap model = new ExtendedModelMap();

		new DuplicatesWebController(duplicateService, mock(PhotoSimilarityService.class), phashBacklogService,
				phashBacklogAsyncRunner, preferences, mock(PhotoSimilarityAsyncRunner.class),
				mock(DuplicateDeletionAsyncRunner.class), mock(DuplicateExclusionService.class), videoWeb())
						.duplicates(new DuplicatesViewRequest("exact", 0, 70, "details", 100, null), null, model);

		Assertions.assertThat(model).containsEntry("pageSize", 100);
		verify(preferences).save("system", DuplicateConstants.PAGE_KEY, SharedConstants.PAGE_SIZE_KEY,
				"100");
		verify(duplicateService).candidates(eq(PageRequest.of(0, 100)), any());
	}

	@Test
	void duplicatesShouldFallBackToTheSavedPageSize() {
		DuplicateService duplicateService = mock(DuplicateService.class);
		var phashBacklogService = mock(PhashBacklogService.class);
		var phashBacklogAsyncRunner = mock(PhashBacklogAsyncRunner.class);
		when(phashBacklogAsyncRunner.liveStatus()).thenReturn(new FingerprintBacklogStatus(0, 0, 0));
		var preferences = mock(UserPagePreferenceService.class);
		when(preferences.find(ArgumentMatchers.any(), ArgumentMatchers.any()))
				.thenReturn(Map.of(SharedConstants.PAGE_SIZE_KEY, "200"));
		when(duplicateService.candidates(eq(PageRequest.of(0, 200)), any())).thenReturn(new PageImpl<>(List.of()));
		ExtendedModelMap model = new ExtendedModelMap();

		new DuplicatesWebController(duplicateService, mock(PhotoSimilarityService.class), phashBacklogService,
				phashBacklogAsyncRunner, preferences, mock(PhotoSimilarityAsyncRunner.class),
				mock(DuplicateDeletionAsyncRunner.class), mock(DuplicateExclusionService.class), videoWeb())
						.duplicates(new DuplicatesViewRequest("exact", 0, 70, "details", null, null), null, model);

		Assertions.assertThat(model).containsEntry("pageSize", 200);
		verify(duplicateService).candidates(eq(PageRequest.of(0, 200)), any());
	}

	@Test
	void duplicatesShouldFallBackToTheSavedViewMode() {
		DuplicateService duplicateService = mock(DuplicateService.class);
		var phashBacklogService = mock(PhashBacklogService.class);
		var phashBacklogAsyncRunner = mock(PhashBacklogAsyncRunner.class);
		when(phashBacklogAsyncRunner.liveStatus()).thenReturn(new FingerprintBacklogStatus(0, 0, 0));
		var preferences = mock(UserPagePreferenceService.class);
		when(preferences.find(ArgumentMatchers.any(), ArgumentMatchers.any()))
				.thenReturn(Map.of(DuplicateConstants.VIEW_KEY, "large"));
		when(duplicateService.candidates(eq(PageRequest.of(0, 50)), any())).thenReturn(new PageImpl<>(List.of()));
		ExtendedModelMap model = new ExtendedModelMap();

		new DuplicatesWebController(duplicateService, mock(PhotoSimilarityService.class), phashBacklogService,
				phashBacklogAsyncRunner, preferences, mock(PhotoSimilarityAsyncRunner.class),
				mock(DuplicateDeletionAsyncRunner.class), mock(DuplicateExclusionService.class), videoWeb())
						.duplicates(new DuplicatesViewRequest("exact", 0, 70, null, null, null), null, model);

		Assertions.assertThat(model).containsEntry("view", "large");
	}

	@Test
	void duplicatesShouldFallBackToTheSavedMinSimilarity() {
		DuplicateService duplicateService = mock(DuplicateService.class);
		PhotoSimilarityService photoSimilarityService = mock(PhotoSimilarityService.class);
		var phashBacklogService = mock(PhashBacklogService.class);
		var phashBacklogAsyncRunner = mock(PhashBacklogAsyncRunner.class);
		when(phashBacklogAsyncRunner.liveStatus()).thenReturn(new FingerprintBacklogStatus(0, 0, 0));
		var preferences = mock(UserPagePreferenceService.class);
		when(preferences.find(ArgumentMatchers.any(), ArgumentMatchers.any()))
				.thenReturn(Map.of(DuplicateConstants.MIN_SIMILARITY_KEY, "100"));
		when(photoSimilarityService.cachedPage(100, PageRequest.of(0, 50)))
				.thenReturn(Optional.of(new PageImpl<>(List.of())));
		ExtendedModelMap model = new ExtendedModelMap();

		new DuplicatesWebController(duplicateService, photoSimilarityService, phashBacklogService,
				phashBacklogAsyncRunner, preferences, mock(PhotoSimilarityAsyncRunner.class),
				mock(DuplicateDeletionAsyncRunner.class), mock(DuplicateExclusionService.class), videoWeb())
						.duplicates(new DuplicatesViewRequest("similar", 0, null, "details", null, null), null, model);

		Assertions.assertThat(model).containsEntry("minSimilarity", 100);
		verify(photoSimilarityService).cachedPage(100, PageRequest.of(0, 50));
	}

	@Test
	void duplicatesShouldBlockSimilarTabWhileFingerprintsArePending() {
		DuplicateService duplicateService = mock(DuplicateService.class);
		PhotoSimilarityService photoSimilarityService = mock(PhotoSimilarityService.class);
		var phashBacklogService = mock(PhashBacklogService.class);
		var phashBacklogAsyncRunner = mock(PhashBacklogAsyncRunner.class);
		when(phashBacklogAsyncRunner.liveStatus()).thenReturn(new FingerprintBacklogStatus(5, 3, 1));
		ExtendedModelMap model = new ExtendedModelMap();

		String view = new DuplicatesWebController(duplicateService, photoSimilarityService, phashBacklogService,
				phashBacklogAsyncRunner, mock(UserPagePreferenceService.class), mock(PhotoSimilarityAsyncRunner.class),
				mock(DuplicateDeletionAsyncRunner.class), mock(DuplicateExclusionService.class), videoWeb())
						.duplicates(new DuplicatesViewRequest("similar", 0, 70, "details", null, null), null, model);

		Assertions.assertThat(view).isEqualTo("app/duplicates");
		Assertions.assertThat(model).containsEntry("phashBlocking", true);
		Assertions.assertThat((List<?>) model.get("groups")).isEmpty();
		verify(photoSimilarityService, never()).groups(ArgumentMatchers.anyInt(), ArgumentMatchers.any());
	}

	@Test
	void deleteStartsTheBackgroundMoveAndReturnsTheInitialSnapshot() {
		var deletionRunner = mock(DuplicateDeletionAsyncRunner.class);
		List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
		when(deletionRunner.start(2)).thenReturn(true);
		when(deletionRunner.isRunning()).thenReturn(true);
		when(deletionRunner.processed()).thenReturn(0);
		when(deletionRunner.total()).thenReturn(2);
		when(deletionRunner.percent()).thenReturn(0);

		var controller = new DuplicatesWebController(mock(DuplicateService.class), mock(PhotoSimilarityService.class),
				mock(PhashBacklogService.class), phashRunner(),
				mock(UserPagePreferenceService.class), mock(PhotoSimilarityAsyncRunner.class), deletionRunner,
				mock(DuplicateExclusionService.class), videoWeb());

		var progress = controller.delete(new DuplicateDeleteRequest(ids));

		Assertions.assertThat(progress.running()).isTrue();
		Assertions.assertThat(progress.total()).isEqualTo(2);
		Assertions.assertThat(progress.result()).isNull();
		verify(deletionRunner).start(2);
		verify(deletionRunner).run(ids);
	}

	@Test
	void deleteDoesNotStartASecondMoveWhileOneIsRunning() {
		var deletionRunner = mock(DuplicateDeletionAsyncRunner.class);
		when(deletionRunner.start(ArgumentMatchers.anyInt())).thenReturn(false);
		when(deletionRunner.isRunning()).thenReturn(true);

		var controller = new DuplicatesWebController(mock(DuplicateService.class), mock(PhotoSimilarityService.class),
				mock(PhashBacklogService.class), phashRunner(),
				mock(UserPagePreferenceService.class), mock(PhotoSimilarityAsyncRunner.class), deletionRunner,
				mock(DuplicateExclusionService.class), videoWeb());

		controller.delete(new DuplicateDeleteRequest(List.of(UUID.randomUUID())));

		verify(deletionRunner, never()).run(ArgumentMatchers.anyCollection());
	}

	@Test
	void deleteProgressReturnsTheFinalResultOnceTheMoveIsDone() {
		var deletionRunner = mock(DuplicateDeletionAsyncRunner.class);
		var result = new DuplicateDeletionResult(true, 2, 2, 0, 0, UUID.randomUUID(), "ok");
		when(deletionRunner.isRunning()).thenReturn(false);
		when(deletionRunner.processed()).thenReturn(2);
		when(deletionRunner.total()).thenReturn(2);
		when(deletionRunner.percent()).thenReturn(100);
		when(deletionRunner.lastResult()).thenReturn(result);

		var controller = new DuplicatesWebController(mock(DuplicateService.class), mock(PhotoSimilarityService.class),
				mock(PhashBacklogService.class), phashRunner(),
				mock(UserPagePreferenceService.class), mock(PhotoSimilarityAsyncRunner.class), deletionRunner,
				mock(DuplicateExclusionService.class), videoWeb());

		var progress = controller.deleteProgress();

		Assertions.assertThat(progress.running()).isFalse();
		Assertions.assertThat(progress.percent()).isEqualTo(100);
		Assertions.assertThat(progress.result()).isSameAs(result);
	}

	@Test
	void retryFingerprintsResetsFailuresAndRedirects() {
		var phashBacklogService = mock(PhashBacklogService.class);
		var phashBacklogAsyncRunner = mock(PhashBacklogAsyncRunner.class);

		String redirect = new DuplicatesWebController(mock(DuplicateService.class), mock(PhotoSimilarityService.class),
				phashBacklogService, phashBacklogAsyncRunner, mock(UserPagePreferenceService.class),
				mock(PhotoSimilarityAsyncRunner.class), mock(DuplicateDeletionAsyncRunner.class),
						mock(DuplicateExclusionService.class), videoWeb()).retryFingerprints();

		Assertions.assertThat(redirect).isEqualTo("redirect:/app/duplicates?tab=similar");
		verify(phashBacklogService).resetFailures();
	}

	@Test
	void rebuildFingerprintsStartsOnlyTheTrackedFingerprintJob() {
		var runner = mock(PhashBacklogAsyncRunner.class);
		when(runner.prepareRebuild()).thenReturn(true);
		var controller = new DuplicatesWebController(mock(DuplicateService.class), mock(PhotoSimilarityService.class),
				mock(PhashBacklogService.class), runner, mock(UserPagePreferenceService.class),
				mock(PhotoSimilarityAsyncRunner.class), mock(DuplicateDeletionAsyncRunner.class),
						mock(DuplicateExclusionService.class), videoWeb());

		Assertions.assertThat(controller.rebuildFingerprints()).isEqualTo("redirect:/app/duplicates?tab=similar");
		verify(runner).prepareRebuild();
		verify(runner).run();
	}

	@Test
	void duplicatesShouldBlockTheWholeScreenDuringAnActiveInventory() {
		DuplicateService duplicateService = mock(DuplicateService.class);
		PhotoSimilarityService photoSimilarityService = mock(PhotoSimilarityService.class);
		var phashBacklogService = mock(PhashBacklogService.class);
		var phashBacklogAsyncRunner = mock(PhashBacklogAsyncRunner.class);
		when(phashBacklogService.inventoryActive()).thenReturn(true);
		ExtendedModelMap model = new ExtendedModelMap();

		String view = new DuplicatesWebController(duplicateService, photoSimilarityService, phashBacklogService,
				phashBacklogAsyncRunner, mock(UserPagePreferenceService.class), mock(PhotoSimilarityAsyncRunner.class),
				mock(DuplicateDeletionAsyncRunner.class), mock(DuplicateExclusionService.class), videoWeb())
						.duplicates(new DuplicatesViewRequest("exact", 0, 70, "details", null, null), null, model);

		Assertions.assertThat(view).isEqualTo("app/duplicates");
		Assertions.assertThat(model).containsEntry("inventoryActive", true);
		Assertions.assertThat((List<?>) model.get("groups")).isEmpty();
		verify(duplicateService, never()).candidates(ArgumentMatchers.any(), ArgumentMatchers.any());
		verify(phashBacklogService, never()).status();
	}

	@Test
	void duplicatesShouldExposeResolutionForEachListedFile() {
		DuplicateService duplicateService = mock(DuplicateService.class);
		PhotoSimilarityService photoSimilarityService = mock(PhotoSimilarityService.class);
		var phashBacklogService = mock(PhashBacklogService.class);
		var phashBacklogAsyncRunner = mock(PhashBacklogAsyncRunner.class);
		when(phashBacklogAsyncRunner.liveStatus()).thenReturn(new FingerprintBacklogStatus(0, 0, 0));

		UUID keepId = UuidV7.fromLegacy(1L);
		UUID candidateId = UuidV7.fromLegacy(2L);

		DuplicateCandidateFileResponse keep = new DuplicateCandidateFileResponse(keepId, "keep.jpg", "jpg", "PHOTO",
				SizeResponse.of(100), "C:/keep.jpg", "C:/", NOW, Verdict.KEEP, Reason.ORIGINAL, 4000, 3000, NOW,
				DateSource.EXIF);

		DuplicateCandidateFileResponse candidate = new DuplicateCandidateFileResponse(candidateId, "dup.jpg", "jpg",
				"PHOTO", SizeResponse.of(100), "C:/dup.jpg", "C:/", NOW, Verdict.DELETE_CANDIDATE, Reason.WHATSAPP_COPY,
				2000, 1500, NOW.minusYears(1), DateSource.FILE_NAME);

		when(duplicateService.candidates(eq(PageRequest.of(0, 50)), any()))
				.thenReturn(new PageImpl<>(List.of(new DuplicateCandidateGroupResponse("sha", 2, SizeResponse.of(100),
						keep, List.of(candidate), List.of())), PageRequest.of(0, 20), 1));

		ExtendedModelMap model = new ExtendedModelMap();

		new DuplicatesWebController(duplicateService, photoSimilarityService, phashBacklogService,
				phashBacklogAsyncRunner, mock(UserPagePreferenceService.class), mock(PhotoSimilarityAsyncRunner.class),
				mock(DuplicateDeletionAsyncRunner.class), mock(DuplicateExclusionService.class), videoWeb())
						.duplicates(new DuplicatesViewRequest("exact", 0, 70, "details", null, null), null, model);

		@SuppressWarnings("unchecked")
		List<DuplicateGroupView> groups = (List<DuplicateGroupView>) model.get("groups");
		DuplicateFileView keepView = groups.getFirst().files().getFirst();

		Assertions.assertThat(keepView.id()).isEqualTo(keepId);
		Assertions.assertThat(keepView.resolution()).isEqualTo("4000 × 3000");
		Assertions.assertThat(keepView.keep()).isTrue();
		Assertions.assertThat(keepView.highlight()).isEqualTo("ORIGINAL");
		Assertions.assertThat(keepView.highlightLabel()).isEqualTo("📷 Original");
		Assertions.assertThat(keepView.reason()).contains("Original");

		DuplicateFileView candidateView = groups.getFirst().files().getLast();
		Assertions.assertThat(candidateView.keep()).isFalse();
		Assertions.assertThat(candidateView.highlight()).isEqualTo("WHATSAPP_COPY");
		Assertions.assertThat(candidateView.highlightLabel()).isEqualTo("📱 WhatsApp");
	}

	@Test
	void duplicatesShouldOpenPdfDetectedByInventoryEvenWithoutExtension() {
		DuplicateService duplicateService = mock(DuplicateService.class);
		PhotoSimilarityService photoSimilarityService = mock(PhotoSimilarityService.class);
		var phashBacklogService = mock(PhashBacklogService.class);
		var phashBacklogAsyncRunner = mock(PhashBacklogAsyncRunner.class);
		when(phashBacklogAsyncRunner.liveStatus()).thenReturn(new FingerprintBacklogStatus(0, 0, 0));

		DuplicateCandidateFileResponse keep = new DuplicateCandidateFileResponse(1L, "document.pdf", "pdf", "PDF",
				SizeResponse.of(100), "C:/document.pdf", "C:/", NOW);
		DuplicateCandidateFileResponse candidate = new DuplicateCandidateFileResponse(2L, "DOC-20220814-WA0019", "",
				"PDF", SizeResponse.of(100), "C:/DOC-20220814-WA0019", "C:/", NOW);
		when(duplicateService.candidates(eq(PageRequest.of(0, 50)), any())).thenReturn(new PageImpl<>(
				List.of(new DuplicateCandidateGroupResponse("sha", 2, SizeResponse.of(100), keep, List.of(candidate))),
				PageRequest.of(0, 20), 1));
		ExtendedModelMap model = new ExtendedModelMap();

		new DuplicatesWebController(duplicateService, photoSimilarityService, phashBacklogService,
				phashBacklogAsyncRunner, mock(UserPagePreferenceService.class), mock(PhotoSimilarityAsyncRunner.class),
				mock(DuplicateDeletionAsyncRunner.class), mock(DuplicateExclusionService.class), videoWeb())
						.duplicates(new DuplicatesViewRequest("exact", 0, 70, "details", null, null), null, model);

		@SuppressWarnings("unchecked")
		List<DuplicateGroupView> groups = (List<DuplicateGroupView>) model.get("groups");
		DuplicateFileView document = groups.getFirst().files().getLast();
		Assertions.assertThat(document.pdf()).isTrue();
		Assertions.assertThat(document.previewable()).isTrue();
		Assertions.assertThat(document.contentUrl()).endsWith("/content");
		Assertions.assertThat(document.lightboxClass()).isEqualTo("js-lightbox-pdf");
	}

	@Test
	void duplicatesShouldKeepOneOfTwoIdenticalOriginalsAndMarkTheCopy() {
		DuplicateService duplicateService = mock(DuplicateService.class);
		PhotoSimilarityService photoSimilarityService = mock(PhotoSimilarityService.class);
		var phashBacklogService = mock(PhashBacklogService.class);
		var phashBacklogAsyncRunner = mock(PhashBacklogAsyncRunner.class);
		var userPagePreferenceService = mock(UserPagePreferenceService.class);
		when(phashBacklogAsyncRunner.liveStatus()).thenReturn(new FingerprintBacklogStatus(0, 0, 0));

		UUID keepId = UuidV7.fromLegacy(1L);
		UUID candidateId = UuidV7.fromLegacy(2L);

		DuplicateCandidateFileResponse keep = new DuplicateCandidateFileResponse(keepId, "keep.jpg", "jpg", "PHOTO",
				SizeResponse.of(100), "C:/keep.jpg", "C:/", NOW, Verdict.KEEP, Reason.ORIGINAL, 4000, 3000, NOW,
				DateSource.EXIF);

		DuplicateCandidateFileResponse candidate = new DuplicateCandidateFileResponse(candidateId, "dup.jpg", "jpg",
				"PHOTO", SizeResponse.of(100), "C:/dup.jpg", "C:/", NOW, Verdict.DELETE_CANDIDATE,
				Reason.IDENTICAL_COPY, 2000, 1500, NOW, DateSource.EXIF);

		when(duplicateService.candidates(eq(PageRequest.of(0, 50)), any()))
				.thenReturn(new PageImpl<>(List.of(new DuplicateCandidateGroupResponse("sha", 2, SizeResponse.of(100),
						keep, List.of(candidate), List.of())), PageRequest.of(0, 20), 1));

		ExtendedModelMap model = new ExtendedModelMap();

		new DuplicatesWebController(duplicateService, photoSimilarityService, phashBacklogService,
				phashBacklogAsyncRunner, userPagePreferenceService, mock(PhotoSimilarityAsyncRunner.class),
				mock(DuplicateDeletionAsyncRunner.class), mock(DuplicateExclusionService.class), videoWeb())
						.duplicates(new DuplicatesViewRequest("exact", 0, 70, "details", null, null), null, model);

		@SuppressWarnings("unchecked")
		List<DuplicateGroupView> groups = (List<DuplicateGroupView>) model.get("groups");
		List<DuplicateFileView> files = groups.getFirst().files();

		Assertions.assertThat(files.getFirst().keep()).isTrue();
		Assertions.assertThat(files.getFirst().highlight()).isEqualTo("ORIGINAL");
		Assertions.assertThat(files.getLast().keep()).isFalse();
		Assertions.assertThat(files.getLast().highlight()).isEqualTo("IDENTICAL_COPY");
	}
}