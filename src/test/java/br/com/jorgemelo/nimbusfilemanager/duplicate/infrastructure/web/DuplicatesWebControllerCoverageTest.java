package br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateCandidateFileResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateCandidateGroupResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateDeleteRequest;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateExcludeRequest;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateExclusionResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateFileView;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicateGroupView;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.DuplicatesViewRequest;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarPhotoGroupResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Reason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Verdict;
import br.com.jorgemelo.nimbusfilemanager.media.domain.enums.MediaTypeFilter;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.SizeResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web.Fixture;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

class DuplicatesWebControllerCoverageTest {

	private static final LocalDateTime NOW = LocalDateTime.parse("2026-07-15T12:00:00");

	@Test
	void duplicatesBindsQueryParamsIncludingTheRepeatedTypesListThroughRealHttp() throws Exception {
		Fixture fixture = new Fixture();
		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(fixture.controller())
				.setViewResolvers(new InternalResourceViewResolver()).build();

		MvcResult result = mockMvc
				.perform(get("/app/duplicates").param("tab", "similar").param("page", "2").param("minSimilarity", "85")
						.param("view", "small").param("size", "100").param("types", "PHOTO").param("types", "VIDEO"))
				.andExpect(status().isOk()).andReturn();

		Map<String, Object> model = result.getModelAndView().getModel();

		Assertions.assertThat(model).containsEntry("activeTab", "similar").containsEntry("view", "small")
				.containsEntry("pageSize", 100).containsEntry("minSimilarity", 85);
		Assertions.assertThat(model.get("selectedTypeFilters"))
				.asInstanceOf(InstanceOfAssertFactories.iterable(String.class))
				.containsExactlyInAnyOrder("PHOTO", "VIDEO");

		verify(fixture.similarity).cachedPage(85, PageRequest.of(2, 100));
		verify(fixture.preferences).save("system", DuplicateConstants.PAGE_KEY,
				DuplicateConstants.TYPE_FILTER_KEY, "PHOTO,VIDEO");
	}

	@Test
	void duplicatesDefaultsToTheFirstPageWhenThePageParamIsAbsent() throws Exception {
		// Regression: the initial screen load carries no ?page=, so record binding sees null
		// for the (formerly primitive int) page component. It must default to 0, not fail (500).
		Fixture fixture = new Fixture();
		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(fixture.controller())
				.setViewResolvers(new InternalResourceViewResolver()).build();

		mockMvc.perform(get("/app/duplicates").param("tab", "similar").param("size", "100"))
				.andExpect(status().isOk());

		verify(fixture.similarity).cachedPage(anyInt(), eq(PageRequest.of(0, 100)));
	}

	@Test
	void invalidSavedPreferencesFallBackSafelyForAnAuthenticatedUserAndKeepPaginationState() {
		Fixture fixture = new Fixture();

		when(fixture.preferences.find("alice", DuplicateConstants.PAGE_KEY)).thenReturn(
				Map.of(DuplicateConstants.TAB_KEY, "invalid", SharedConstants.PAGE_SIZE_KEY, "also-invalid",
						DuplicateConstants.MIN_SIMILARITY_KEY, "invalid-too", DuplicateConstants.VIEW_KEY,
						"unknown"));
		when(fixture.duplicates.candidates(eq(PageRequest.of(1, 50)), any()))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 50), 101));

		ExtendedModelMap model = new ExtendedModelMap();

		fixture.controller().duplicates(new DuplicatesViewRequest(null, 1, null, null, null, null),
				new TestingAuthenticationToken("alice", "password"), model);

		Assertions.assertThat(model).containsEntry("activeTab", "exact").containsEntry("view", "details")
				.containsEntry("pageSize", 50)
				.containsEntry("minSimilarity", DuplicateConstants.MIN_SIMILARITY_PERCENT)
				.containsEntry("hasPrevious", true).containsEntry("hasNext", true);
	}

	@Test
	void invalidNumericPreferenceValuesAreClampedOrIgnoredWithoutChangingTheRequestedScreen() {
		Fixture fixture = new Fixture();

		when(fixture.preferences.find("system", DuplicateConstants.PAGE_KEY)).thenReturn(Map.of(
				SharedConstants.PAGE_SIZE_KEY, "75",
				DuplicateConstants.MIN_SIMILARITY_KEY, "999"));
		when(fixture.duplicates.candidates(eq(PageRequest.of(0, 50)), any())).thenReturn(Page.empty());

		ExtendedModelMap model = new ExtendedModelMap();

		fixture.controller().duplicates(new DuplicatesViewRequest("exact", 0, null, "details", 75, null), null, model);

		Assertions.assertThat(model).containsEntry("pageSize", 50)
				.containsEntry("minSimilarity", DuplicateConstants.MAX_SIMILARITY_PERCENT);
	}

	@Test
	void applyingTheTypeFilterPersistsTheGroupsAndQueriesTheExpandedFileTypes() {
		Fixture fixture = new Fixture();

		when(fixture.duplicates.candidates(any(), any())).thenReturn(Page.empty());

		ExtendedModelMap model = new ExtendedModelMap();

		fixture.controller().duplicates(new DuplicatesViewRequest("exact", 0, null, "details", null,
				List.of(MediaTypeFilter.PHOTO, MediaTypeFilter.DOCS)),
				new TestingAuthenticationToken("alice", "password"), model);

		verify(fixture.preferences).save("alice", DuplicateConstants.PAGE_KEY,
				DuplicateConstants.TYPE_FILTER_KEY, "PHOTO,DOCS");
		verify(fixture.duplicates).candidates(PageRequest.of(0, 50),
				MediaTypeFilter.fileTypesOf(List.of(MediaTypeFilter.PHOTO, MediaTypeFilter.DOCS)));
		Assertions.assertThat(model.get("selectedTypeFilters"))
				.asInstanceOf(InstanceOfAssertFactories.iterable(String.class))
				.containsExactlyInAnyOrder("PHOTO", "DOCS");
	}

	@Test
	void submittingNoTypesMeansShowAllAndPersistsEveryGroup() {
		Fixture fixture = new Fixture();

		when(fixture.duplicates.candidates(any(), any())).thenReturn(Page.empty());

		ExtendedModelMap model = new ExtendedModelMap();

		fixture.controller().duplicates(new DuplicatesViewRequest("exact", 0, null, "details", null, List.of()),
				new TestingAuthenticationToken("alice", "password"), model);

		verify(fixture.preferences).save("alice", DuplicateConstants.PAGE_KEY,
				DuplicateConstants.TYPE_FILTER_KEY, "PHOTO,VIDEO,AUDIO,DOCS,OTHERS");
		Assertions.assertThat(model.get("selectedTypeFilters"))
				.asInstanceOf(InstanceOfAssertFactories.iterable(String.class))
				.containsExactlyInAnyOrder("PHOTO", "VIDEO", "AUDIO", "DOCS", "OTHERS");
	}

	@Test
	void anInvalidSavedTypeFilterFallsBackToEveryGroup() {
		Fixture fixture = new Fixture();

		when(fixture.preferences.find("alice", DuplicateConstants.PAGE_KEY))
				.thenReturn(Map.of(DuplicateConstants.TYPE_FILTER_KEY, "BOGUS,,ALSO_BAD"));
		when(fixture.duplicates.candidates(any(), any())).thenReturn(Page.empty());

		ExtendedModelMap model = new ExtendedModelMap();

		fixture.controller().duplicates(new DuplicatesViewRequest("exact", 0, null, "details", null, null),
				new TestingAuthenticationToken("alice", "password"), model);

		Assertions.assertThat(model.get("selectedTypeFilters"))
				.asInstanceOf(InstanceOfAssertFactories.iterable(String.class))
				.containsExactlyInAnyOrder("PHOTO", "VIDEO", "AUDIO", "DOCS", "OTHERS");
	}

	@Test
	void aSavedTypeFilterIsReadBackWhenTheRequestOmitsIt() {
		Fixture fixture = new Fixture();

		when(fixture.preferences.find("alice", DuplicateConstants.PAGE_KEY))
				.thenReturn(Map.of(DuplicateConstants.TYPE_FILTER_KEY, "VIDEO,AUDIO"));
		when(fixture.duplicates.candidates(any(), any())).thenReturn(Page.empty());

		ExtendedModelMap model = new ExtendedModelMap();

		fixture.controller().duplicates(new DuplicatesViewRequest("exact", 0, null, "details", null, null),
				new TestingAuthenticationToken("alice", "password"), model);

		verify(fixture.duplicates).candidates(PageRequest.of(0, 50),
				MediaTypeFilter.fileTypesOf(List.of(MediaTypeFilter.VIDEO, MediaTypeFilter.AUDIO)));
		Assertions.assertThat(model.get("selectedTypeFilters"))
				.asInstanceOf(InstanceOfAssertFactories.iterable(String.class))
				.containsExactlyInAnyOrder("VIDEO", "AUDIO");
	}

	@Test
	void stoppedFingerprintJobsAndEmptyDeleteRequestsDoNotLaunchBackgroundWork() {
		Fixture fixture = new Fixture();

		when(fixture.phashRunner.start()).thenReturn(false);
		when(fixture.phashRunner.prepareRebuild()).thenReturn(false);
		when(fixture.deletionRunner.start(0)).thenReturn(false);

		Assertions.assertThat(fixture.controller().retryFingerprints())
				.isEqualTo("redirect:/app/duplicates?tab=similar");
		Assertions.assertThat(fixture.controller().rebuildFingerprints())
				.isEqualTo("redirect:/app/duplicates?tab=similar");

		fixture.controller().delete(null);
		fixture.controller().delete(new DuplicateDeleteRequest(null));

		verify(fixture.phashRunner, never()).run();
		verify(fixture.deletionRunner, never()).run(anyList());
		verify(fixture.deletionRunner, times(2)).start(0);
	}

	@Test
	void excludeFileHidesItFromComparisonAndInvalidatesTheSimilarCache() {
		Fixture fixture = new Fixture();
		UUID publicId = UUID.randomUUID();
		when(fixture.exclusions.excludeFile(publicId)).thenReturn(true);

		DuplicateExclusionResponse response = fixture.controller()
				.excludeFile(new DuplicateExcludeRequest(publicId, null));

		Assertions.assertThat(response.created()).isTrue();
		verify(fixture.exclusions).excludeFile(publicId);
		verify(fixture.similarity).invalidateCache();
	}

	@Test
	void excludeFolderHidesItFromComparisonAndInvalidatesTheSimilarCache() {
		Fixture fixture = new Fixture();
		when(fixture.exclusions.excludeFolder("C:/Fotos")).thenReturn(true);

		DuplicateExclusionResponse response = fixture.controller()
				.excludeFolder(new DuplicateExcludeRequest(null, "C:/Fotos"));

		Assertions.assertThat(response.created()).isTrue();
		verify(fixture.exclusions).excludeFolder("C:/Fotos");
		verify(fixture.similarity).invalidateCache();
	}

	@Test
	void excludeEndpointsTolerateAMissingBody() {
		Fixture fixture = new Fixture();

		fixture.controller().excludeFile(null);
		fixture.controller().excludeFolder(null);

		verify(fixture.exclusions).excludeFile(null);
		verify(fixture.exclusions).excludeFolder(null);
		verify(fixture.similarity, times(2)).invalidateCache();
	}

	@Test
	void retryRunsTheFingerprintBacklogWhenTheJobStartsSuccessfully() {
		Fixture fixture = new Fixture();

		when(fixture.phashRunner.start()).thenReturn(true);

		fixture.controller().retryFingerprints();

		verify(fixture.phashRunner).run();
	}

	@Test
	void blankSavedPreferencesAndAnInvalidRequestedTabUseDefaults() {
		Fixture fixture = new Fixture();

		when(fixture.preferences.find("system", DuplicateConstants.PAGE_KEY))
				.thenReturn(Map.of(DuplicateConstants.TAB_KEY, " ", SharedConstants.PAGE_SIZE_KEY,
						" ", DuplicateConstants.MIN_SIMILARITY_KEY, " ", DuplicateConstants.VIEW_KEY, " "));

		ExtendedModelMap model = new ExtendedModelMap();

		fixture.controller().duplicates(new DuplicatesViewRequest("not-a-tab", 0, null, null, null, null), null, model);

		Assertions.assertThat(model).containsEntry("activeTab", "exact").containsEntry("pageSize", 50)
				.containsEntry("minSimilarity", DuplicateConstants.MIN_SIMILARITY_PERCENT)
				.containsEntry("view", "details");
	}

	@Test
	void runningFingerprintJobIsNotOfferedForRebuild() {
		Fixture fixture = new Fixture();

		when(fixture.phashRunner.isRunning()).thenReturn(true);

		ExtendedModelMap model = new ExtendedModelMap();

		fixture.controller().duplicates(new DuplicatesViewRequest("exact", 0, 70, "details", null, null), null, model);

		Assertions.assertThat(model).containsEntry("phashRunning", true).containsEntry("phashRebuildAvailable", false);
	}

	@Test
	void absentRequestedAndSavedTabAndSimilarityUseTheirSafeDefaults() {
		Fixture fixture = new Fixture();

		ExtendedModelMap model = new ExtendedModelMap();

		fixture.controller().duplicates(new DuplicatesViewRequest(null, 0, null, "details", null, null), null, model);

		Assertions.assertThat(model).containsEntry("activeTab", "exact")
				.containsEntry("minSimilarity", DuplicateConstants.MIN_SIMILARITY_PERCENT);
	}

	@Test
	void exactSingleFileGroupUsesTheSingularHeading() {
		Fixture fixture = new Fixture();

		DuplicateCandidateFileResponse only = file(1, "only.pdf", "pdf", "PDF");

		when(fixture.duplicates.candidates(eq(PageRequest.of(0, 50)), any())).thenReturn(new PageImpl<>(
				List.of(new DuplicateCandidateGroupResponse("sha", 1, SizeResponse.of(0), only, List.of()))));

		ExtendedModelMap model = new ExtendedModelMap();

		fixture.controller().duplicates(new DuplicatesViewRequest("exact", 0, 70, "details", null, null), null, model);

		Assertions.assertThat(groups(model).getFirst().headerText()).isEqualTo("1 arquivo idêntico");
	}

	@Test
	void similarMultiFileGroupUsesThePluralHeading() {
		Fixture fixture = new Fixture();

		DuplicateCandidateFileResponse keep = file(1, "keep.jpg", "jpg", "PHOTO");
		DuplicateCandidateFileResponse candidate = file(2, "candidate.jpg", "jpg", "PHOTO");

		when(fixture.similarity.cachedPage(70, PageRequest.of(0, 50)))
				.thenReturn(Optional.of(new PageImpl<>(List.of(new SimilarPhotoGroupResponse("similar", 2, 90,
						SizeResponse.of(10), keep, List.of(candidate))))));

		ExtendedModelMap model = new ExtendedModelMap();

		fixture.controller().duplicates(new DuplicatesViewRequest("similar", 0, 70, "details", null, null), null, model);

		Assertions.assertThat(groups(model).getFirst().headerText()).isEqualTo("2 fotos semelhantes");
	}

	@Test
	void fileCardsCoverEveryPreviewKindDateSourceAndRecommendationReason() {
		Fixture fixture = new Fixture();

		DateSource[] sources = DateSource.values();

		List<DuplicateCandidateFileResponse> files = List.of(
				file(1, "document.pdf", "pdf", "PDF", Verdict.KEEP, Reason.ORIGINAL, 1920, 1080, sources[0]),
				file(2, "notes.txt", "txt", "TEXT", Verdict.DELETE_CANDIDATE, Reason.WHATSAPP_COPY, 1920, 1080,
						sources[1]),
				file(3, "sound.mp3", "mp3", "AUDIO", Verdict.DELETE_CANDIDATE, Reason.EDITED_COPY, null, 1080,
						sources[2]),
				file(4, "movie.mp4", "mp4", "VIDEO", Verdict.DELETE_CANDIDATE, Reason.DERIVATIVE, 1920, null,
						sources[3]),
				file(5, "photo.jpg", "jpg", "PHOTO", Verdict.DELETE_CANDIDATE, Reason.IDENTICAL_COPY, 1920, 1080,
						sources[4]),
				file(6, "archive.bin", "bin", "OTHER", Verdict.REVIEW, Reason.REVIEW_NO_CLEAR_ORIGINAL, 1920, 1080,
						sources[5]),
				file(7, "report.docx", "docx", "WORD", Verdict.KEEP, Reason.BEST_IN_GROUP, 1920, 1080, sources[6]),
				file(8, "unknown", "", null, Verdict.REVIEW, null, 1920, 1080, sources[7]));

		when(fixture.duplicates.candidates(eq(PageRequest.of(0, 50)), any())).thenReturn(new PageImpl<>(List.of(
				new DuplicateCandidateGroupResponse("all-kinds", 6, SizeResponse.of(400), files.get(0),
						files.subList(1, 5), List.of(files.get(5))),
				new DuplicateCandidateGroupResponse("review-kinds", 2, SizeResponse.of(0), files.get(6), List.of(),
						List.of(files.get(7))))));

		ExtendedModelMap model = new ExtendedModelMap();

		fixture.controller().duplicates(new DuplicatesViewRequest("exact", 0, 70, "details", null, null), null, model);

		List<DuplicateFileView> views = groups(model).stream().flatMap(group -> group.files().stream()).toList();

		Assertions.assertThat(views).extracting(DuplicateFileView::iconLabel).contains("PDF", "Texto", "Áudio", "Vídeo",
				"Imagem", "Arquivo", "Word");
		Assertions.assertThat(views).extracting(DuplicateFileView::openTitle).contains("Abrir PDF",
				"Abrir arquivo de texto", "Reproduzir áudio", "Abrir arquivo");
		Assertions.assertThat(views).extracting(DuplicateFileView::dateSourceLabel).containsExactly("EXIF", "mídia",
				"nome", "nome ✓", "criação", "arquivo", "pasta", "sem data");
		Assertions.assertThat(views).extracting(DuplicateFileView::dateSourceBadgeClass).containsExactly("ok", "ok",
				"info", "info", "muted", "muted", "info", "warn");
		Assertions.assertThat(views).extracting(DuplicateFileView::lightboxClass).contains("js-lightbox-pdf",
				"js-lightbox-text", "js-lightbox-audio", "");
		Assertions.assertThat(views).extracting(DuplicateFileView::previewable).contains(true, false);
		Assertions.assertThat(views).extracting(DuplicateFileView::highlight).containsExactly("ORIGINAL",
				"WHATSAPP_COPY", "EDITED_COPY", "DERIVATIVE", "IDENTICAL_COPY", "REVIEW_NO_CLEAR_ORIGINAL",
				"BEST_IN_GROUP", null);
		Assertions.assertThat(views.get(2).resolution()).isNull();
		Assertions.assertThat(views.get(3).resolution()).isNull();
		Assertions.assertThat(views.getLast().contentUrl()).isNull();
	}

	@SuppressWarnings("unchecked")
	private static List<DuplicateGroupView> groups(ExtendedModelMap model) {
		return (List<DuplicateGroupView>) model.get("groups");
	}

	private static DuplicateCandidateFileResponse file(long id, String name, String extension, String type) {
		return new DuplicateCandidateFileResponse(id, name, extension, type, SizeResponse.of(100), "C:/" + name, "C:/",
				NOW);
	}

	private static DuplicateCandidateFileResponse file(long id, String name, String extension, String type,
			Verdict verdict, Reason reason, Integer width, Integer height, DateSource dateSource) {
		return new DuplicateCandidateFileResponse(UuidV7.fromLegacy(id), name, extension, type, SizeResponse.of(100),
				"C:/" + name, "C:/", NOW, verdict, reason, width, height, NOW.minusDays(id), dateSource);
	}
}