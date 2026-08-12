package br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.ui.ExtendedModelMap;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedMember;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateDeleteRequest;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateDeletionProgress;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateExcludeRequest;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateFileView;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateGroupView;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicatesViewRequest;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.PublishedGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.PublishedMember;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityView;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Reason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Verdict;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.SimilarityMemberFile;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web.Fixture;

/**
 * The Duplicados screen, now that similarity is something it reads rather than
 * something it starts.
 *
 * <p>
 * What changed is where the answer comes from; what did not is anything about
 * tabs, page sizes, view modes, thresholds or the exact-duplicates tab. Both are
 * pinned here - the second group deliberately, because a migration that quietly
 * changes the screen around it is the failure this slice most needed to avoid.
 */
class DuplicatesWebControllerTest {

	private static final LocalDateTime NOW = LocalDateTime.parse("2026-07-08T12:00:00");
	/** When the file was last written to, which is a moment on the timeline. */
	private static final Instant WRITTEN_AT = Instant.parse("2026-07-08T12:00:00Z");

	private final Fixture fixture = new Fixture();

	private final DuplicatesWebController controller = fixture.controller();

	@Test
	void showsThePublishedAnalysisOfTheSimilarTab() {
		when(fixture.similarityView.photos(anyInt(), any())).thenReturn(view(published(), true, false));

		ExtendedModelMap model = new ExtendedModelMap();

		controller.duplicates(request("similar"), null, model);

		Assertions.assertThat(model.getAttribute("similarityPublished")).isEqualTo(true);
		Assertions.assertThat(model.getAttribute("similarityComputing")).isEqualTo(false);
		Assertions.assertThat(groups(model)).hasSize(1);
		Assertions.assertThat(groups(model).getFirst().files()).hasSize(2);
	}

	/**
	 * The page is built without ever asking whether the library moved since the
	 * analysis. That question identifies the whole eligible library to compare one
	 * digest - 2,5 s of the 2,9 s this navigation used to cost - and nothing
	 * rendered here depends on the answer, so the screen ships with the results and
	 * asks afterwards, at the address it is given.
	 */
	@Test
	void rendersTheAnalysisWithoutWaitingToLearnWhetherTheLibraryMovedSince() {
		when(fixture.similarityView.photos(anyInt(), any())).thenReturn(view(published(), true, false));

		ExtendedModelMap model = new ExtendedModelMap();

		controller.duplicates(request("similar"), null, model);

		Assertions.assertThat(groups(model)).hasSize(1);
		Assertions.assertThat(model.getAttribute("similarityFreshnessUrl")).isEqualTo(
				"/api/duplicates/similar-photos/freshness?minSimilarity=" + model.getAttribute("minSimilarity"));

		verify(fixture.similarityView, never()).outdated(any(), anyInt());
	}

	/** A new analysis running never replaces the one that is published. */
	@Test
	void keepsTheAnalysisOnScreenWhileANewOneIsBeingComputed() {
		when(fixture.similarityView.photos(anyInt(), any())).thenReturn(view(published(), true, true));

		ExtendedModelMap model = new ExtendedModelMap();

		controller.duplicates(request("similar"), null, model);

		Assertions.assertThat(model.getAttribute("similarityComputing")).isEqualTo(true);
		Assertions.assertThat(model.getAttribute("similarityPublished")).isEqualTo(true);
		Assertions.assertThat(groups(model)).hasSize(1);
	}

	@Test
	void showsTheProcessingStateWhenNothingWasEverPublished() {
		when(fixture.similarityView.photos(anyInt(), any()))
				.thenReturn(new SimilarityView(Page.empty(), false, true, 120, 0, 8000, false));

		ExtendedModelMap model = new ExtendedModelMap();

		controller.duplicates(request("similar"), null, model);

		Assertions.assertThat(model.getAttribute("similarityPublished")).isEqualTo(false);
		Assertions.assertThat(model.getAttribute("similarityComputing")).isEqualTo(true);
		Assertions.assertThat(groups(model)).isEmpty();
	}

	/**
	 * The coverage the analysis really had. A library above the candidate cap is
	 * analysed in part, and the screen has the numbers to say so instead of
	 * implying everything was compared (V28a).
	 */
	@Test
	void publishesTheRealCoverageOfTheAnalysis() {
		when(fixture.similarityView.photos(anyInt(), any()))
				.thenReturn(new SimilarityView(published(), true, false, 98000, 8000, 8000, false));

		ExtendedModelMap model = new ExtendedModelMap();

		controller.duplicates(request("similar"), null, model);

		Assertions.assertThat(model.getAttribute("similarityEligible")).isEqualTo(98000);
		Assertions.assertThat(model.getAttribute("similarityAnalyzed")).isEqualTo(8000);
		Assertions.assertThat(model.getAttribute("similarityCandidateLimit")).isEqualTo(8000);
		Assertions.assertThat(model.getAttribute("similarityCoverageComplete")).isEqualTo(false);
	}

	/**
	 * A member quarantined after the analysis keeps its place in the group - the
	 * analysis was not wrong - and arrives flagged so the screen offers nothing
	 * over it.
	 */
	@Test
	void marksMembersThatCanNoLongerBeActedUpon() {
		PublishedMember gone = new PublishedMember(
				new AnalyzedMember(UUID.randomUUID(), Verdict.DELETE_CANDIDATE, Reason.IDENTICAL_COPY),
				file("quarantined.jpg", LifecycleStatus.DELETED), false);

		Page<PublishedGroup> page = new PageImpl<>(
				List.of(new PublishedGroup("7", 96, 2048L, List.of(keepMember(), gone), 1)));

		when(fixture.similarityView.photos(anyInt(), any())).thenReturn(view(page, true, false));

		ExtendedModelMap model = new ExtendedModelMap();

		controller.duplicates(request("similar"), null, model);

		List<DuplicateFileView> files = groups(model).getFirst().files();

		Assertions.assertThat(files).hasSize(2);
		Assertions.assertThat(files.get(0).actionable()).isTrue();
		Assertions.assertThat(files.get(1).actionable()).isFalse();
	}

	/** A member whose catalog row is gone has nothing to draw, and is not drawn. */
	@Test
	void doesNotRenderAMemberWhoseCatalogRecordDisappeared() {
		PublishedMember vanished = new PublishedMember(
				new AnalyzedMember(UUID.randomUUID(), Verdict.DELETE_CANDIDATE, Reason.IDENTICAL_COPY), null, false);

		Page<PublishedGroup> page = new PageImpl<>(
				List.of(new PublishedGroup("7", 96, 2048L, List.of(keepMember(), vanished), 1)));

		when(fixture.similarityView.photos(anyInt(), any())).thenReturn(view(page, true, false));

		ExtendedModelMap model = new ExtendedModelMap();

		controller.duplicates(request("similar"), null, model);

		DuplicateGroupView group = groups(model).getFirst();

		Assertions.assertThat(group.files()).hasSize(1);

		// The header still counts what the analysis found, not what survived.
		Assertions.assertThat(group.headerText()).contains("2");
	}

	@Test
	void showsThePublishedAnalysisOfTheVideosTab() {
		when(fixture.similarityView.videos(anyInt(), any())).thenReturn(view(published(), true, false));

		ExtendedModelMap model = new ExtendedModelMap();

		controller.duplicates(request("videos"), null, model);

		Assertions.assertThat(groups(model)).hasSize(1);

		// The heading has to say videos, not photos: the same view type serves both
		// tabs, and what tells them apart is the flag the controller passes.
		Assertions.assertThat(groups(model).getFirst().headerText()).contains("vídeo");

		verify(fixture.similarityView).videos(anyInt(), any());
		verify(fixture.similarityView, never()).photos(anyInt(), any());
	}

	@Test
	void aSingleVideoGroupUsesTheSingularHeading() {
		Page<PublishedGroup> page = new PageImpl<>(
				List.of(new PublishedGroup("7", 96, 2048L, List.of(keepMember()), 1)));

		when(fixture.similarityView.videos(anyInt(), any())).thenReturn(view(page, true, false));

		ExtendedModelMap model = new ExtendedModelMap();

		controller.duplicates(request("videos"), null, model);

		Assertions.assertThat(groups(model).getFirst().headerText()).isEqualTo("1 vídeo semelhante");
	}

	@Test
	void askingForANewAnalysisQueuesItAndReturnsToTheTab() {
		Assertions.assertThat(controller.analyzePhotos(90, null)).isEqualTo("redirect:/app/duplicates?tab=similar");
		Assertions.assertThat(controller.analyzeVideos(85, null)).isEqualTo("redirect:/app/duplicates?tab=videos");

		verify(fixture.similarityLauncher).launchPhotos(90);
		verify(fixture.similarityLauncher).launchVideos(85);
	}

	/** Nothing is grouped while the fingerprints those groups need are missing. */
	@Test
	void doesNotShowGroupsWhileFingerprintsAreStillBeingComputed() {
		when(fixture.phash.status()).thenReturn(new FingerprintBacklogStatus(50, 10, 0));
		when(fixture.similarityView.photos(anyInt(), any())).thenReturn(view(published(), true, false));

		ExtendedModelMap model = new ExtendedModelMap();

		controller.duplicates(request("similar"), null, model);

		Assertions.assertThat(groups(model)).isEmpty();
		Assertions.assertThat(model.getAttribute("phashBlocking")).isEqualTo(true);
	}

	@Test
	void clampsAThresholdBelowTheFloor() {
		ExtendedModelMap model = new ExtendedModelMap();

		controller.duplicates(new DuplicatesViewRequest("similar", 0, 10, "details", 50, null), null, model);

		Assertions.assertThat(model.getAttribute("minSimilarity"))
				.isEqualTo(DuplicateConstants.MIN_SIMILARITY_PERCENT);
	}

	@Test
	void remembersTheTabThePageSizeTheViewAndTheThreshold() {
		ExtendedModelMap model = new ExtendedModelMap();

		controller.duplicates(new DuplicatesViewRequest("videos", 0, 95, "large", 100, null), null, model);

		verify(fixture.preferences).save(any(), eq(DuplicateConstants.PAGE_KEY), eq("tab"), eq("videos"));
		verify(fixture.preferences).save(any(), eq(DuplicateConstants.PAGE_KEY), eq(SharedConstants.PAGE_SIZE_KEY),
				eq("100"));
		verify(fixture.preferences).save(any(), eq(DuplicateConstants.PAGE_KEY),
				eq(DuplicateConstants.MIN_SIMILARITY_VIDEO_KEY), eq("95"));
		verify(fixture.preferences).save(any(), eq(DuplicateConstants.PAGE_KEY), eq("view"), eq("large"));
	}

	@Test
	void fallsBackToTheSavedPreferencesWhenTheRequestOmitsThem() {
		when(fixture.preferences.find(any(), eq(DuplicateConstants.PAGE_KEY)))
				.thenReturn(Map.of("tab", "similar", SharedConstants.PAGE_SIZE_KEY, "200",
						DuplicateConstants.MIN_SIMILARITY_KEY, "85", "view", "xlarge"));
		when(fixture.similarityView.photos(anyInt(), any())).thenReturn(view(published(), true, false));

		ExtendedModelMap model = new ExtendedModelMap();

		controller.duplicates(new DuplicatesViewRequest(null, null, null, null, null, null), null, model);

		Assertions.assertThat(model.getAttribute("activeTab")).isEqualTo("similar");
		Assertions.assertThat(model.getAttribute(SharedConstants.PAGE_SIZE_KEY)).isEqualTo(200);
		Assertions.assertThat(model.getAttribute("minSimilarity")).isEqualTo(85);
		Assertions.assertThat(model.getAttribute("view")).isEqualTo("xlarge");
	}

	@Test
	void deleteQueuesTheMoveAndAnswersWithTheCurrentProgress() {
		UUID id = UUID.randomUUID();

		when(fixture.deletionProgress.snapshot()).thenReturn(new DuplicateDeletionProgress(true, 1, 2, 50.0, null));

		Assertions.assertThat(controller.delete(new DuplicateDeleteRequest(List.of(id))).running()).isTrue();

		verify(fixture.deletionLauncher).launch(List.of(id));
	}

	@Test
	void deleteQueuesNothingWhenNoFileWasNamed() {
		when(fixture.deletionProgress.snapshot()).thenReturn(new DuplicateDeletionProgress(false, 0, 0, 0.0, null));

		controller.delete(new DuplicateDeleteRequest(List.of()));

		verify(fixture.deletionLauncher, never()).launch(any());
	}

	/**
	 * Excluding no longer clears any cache: the exclusion is part of the analysis's
	 * identity, so a result computed before it simply stops answering the current
	 * question.
	 */
	@Test
	void excludingAFileOnlyRecordsTheExclusion() {
		when(fixture.exclusions.excludeFile(any())).thenReturn(true);

		Assertions.assertThat(controller.excludeFile(new DuplicateExcludeRequest(UUID.randomUUID(), null)).created())
				.isTrue();

		verify(fixture.exclusions).excludeFile(any());
	}

	private DuplicatesViewRequest request(String tab) {
		return new DuplicatesViewRequest(tab, 0, 90, "details", 50, null);
	}

	@SuppressWarnings("unchecked")
	private List<DuplicateGroupView> groups(ExtendedModelMap model) {
		return (List<DuplicateGroupView>) model.getAttribute("groups");
	}

	private SimilarityView view(Page<PublishedGroup> groups, boolean published, boolean analyzing) {
		return new SimilarityView(groups, published, analyzing, 2, 2, 8000, true);
	}

	private Page<PublishedGroup> published() {
		return new PageImpl<>(
				List.of(new PublishedGroup("7", 96, 2048L, List.of(keepMember(), deleteMember()), 2)));
	}

	private PublishedMember keepMember() {
		return new PublishedMember(new AnalyzedMember(UUID.randomUUID(), Verdict.KEEP, Reason.ORIGINAL),
				file("original.jpg", LifecycleStatus.ACTIVE), true);
	}

	private PublishedMember deleteMember() {
		return new PublishedMember(
				new AnalyzedMember(UUID.randomUUID(), Verdict.DELETE_CANDIDATE, Reason.IDENTICAL_COPY),
				file("copy.jpg", LifecycleStatus.ACTIVE), true);
	}

	private SimilarityMemberFile file(String name, LifecycleStatus status) {
		return new SimilarityMemberFile(UUID.randomUUID(), name, "jpg", "PHOTO", 1024L, "C:/fotos/" + name, "C:/fotos",
				WRITTEN_AT, 1920, 1080, NOW, DateSource.EXIF, status);
	}

	/**
	 * The verdict the screen may not deliver from a fraction of the library.
	 *
	 * <p>
	 * An analysis can have compared every file it was <em>able</em> to compare -
	 * which is all the internal coverage flag claims - while most of the library
	 * still has no fingerprint. Reading those two as one produced "no similar
	 * videos found" over 188 of 5.860 videos: true about the analysis, and read by
	 * everyone as a statement about the library.
	 */
	@Test
	void aCompleteAnalysisOverAnIncompleteLibraryIsNotAFinalVerdict() {
		when(fixture.phash.status()).thenReturn(new FingerprintBacklogStatus(5_360, 500, 0));
		when(fixture.similarityView.photos(anyInt(), any())).thenReturn(analysed(500, 500));

		ExtendedModelMap model = new ExtendedModelMap();

		controller.duplicates(request("similar"), null, model);

		Assertions.assertThat(model.getAttribute("similarityCoverageComplete"))
				.as("the analysis did cover everything it could compare").isEqualTo(true);
		Assertions.assertThat(model.getAttribute("similarityLibraryComplete"))
				.as("but the library is 500 of 5.860 fingerprinted").isEqualTo(false);
	}

	/** With every fingerprint in place and the analysis complete, it is a verdict. */
	@Test
	void aCompleteAnalysisOverACompleteLibraryIsAFinalVerdict() {
		when(fixture.phash.status()).thenReturn(new FingerprintBacklogStatus(0, 5_860, 0));
		when(fixture.similarityView.photos(anyInt(), any())).thenReturn(analysed(5_860, 5_860));

		ExtendedModelMap model = new ExtendedModelMap();

		controller.duplicates(request("similar"), null, model);

		Assertions.assertThat(model.getAttribute("similarityCoverageComplete")).isEqualTo(true);
		Assertions.assertThat(model.getAttribute("similarityLibraryComplete")).isEqualTo(true);
	}

	/**
	 * A fingerprint that failed for good is a file that will never be compared, so
	 * the library was never examined in full - even with nothing left pending.
	 */
	@Test
	void exhaustedFailuresKeepTheLibraryFromCountingAsFullyExamined() {
		when(fixture.phash.status()).thenReturn(new FingerprintBacklogStatus(0, 5_858, 2));
		when(fixture.similarityView.photos(anyInt(), any())).thenReturn(analysed(5_858, 5_858));

		ExtendedModelMap model = new ExtendedModelMap();

		controller.duplicates(request("similar"), null, model);

		Assertions.assertThat(model.getAttribute("similarityLibraryComplete")).isEqualTo(false);
	}

	/** The same rule for videos, which is where it was first seen going wrong. */
	@Test
	void theVideoTabIsHeldToTheSameRule() {
		when(fixture.videoBacklog.status()).thenReturn(new FingerprintBacklogStatus(5_672, 188, 0));
		when(fixture.similarityView.videos(anyInt(), any())).thenReturn(analysed(188, 188));

		ExtendedModelMap model = new ExtendedModelMap();

		controller.duplicates(request("videos"), null, model);

		Assertions.assertThat(model.getAttribute("similarityCoverageComplete")).isEqualTo(true);
		Assertions.assertThat(model.getAttribute("similarityLibraryComplete")).isEqualTo(false);
	}

	/**
	 * Groups already found stay on screen while the rest of the library is still
	 * being fingerprinted: a partial answer is worth working with, and hiding it
	 * would turn an incomplete backlog into a blocked screen.
	 */
	@Test
	void groupsAlreadyFoundSurviveAnIncompleteLibrary() {
		when(fixture.videoBacklog.status()).thenReturn(new FingerprintBacklogStatus(0, 188, 0));
		when(fixture.similarityView.videos(anyInt(), any()))
				.thenReturn(new SimilarityView(published(), true, false, 188, 188, 8000, true));

		ExtendedModelMap model = new ExtendedModelMap();

		controller.duplicates(request("videos"), null, model);

		Assertions.assertThat(groups(model)).as("what was found is still shown").hasSize(1);
	}

	/** An analysis of one eligible population, with no groups in it. */
	private SimilarityView analysed(int eligible, int analyzed) {
		return new SimilarityView(Page.empty(), true, false, eligible, analyzed, 8000, true);
	}
}