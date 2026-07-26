package br.com.jorgemelo.nimbusfilemanager.conversion.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.NameAffixPosition;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.ConversionCandidateService;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.ConversionCommitService;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.VideoConversionAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.constants.ConversionConstants;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionCandidateView;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionOptions;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionProgress;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionRequest;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionResult;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.AudioHandling;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionQuality;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.OriginalDisposition;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantinePurgeService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;

class ConversionWebControllerTest {

	private final ConversionCandidateService conversionCandidateService = mock(ConversionCandidateService.class);
	private final VideoConversionAsyncRunner runner = mock(VideoConversionAsyncRunner.class);
	private final ConversionCommitService conversionCommitService = mock(ConversionCommitService.class);
	private final QuarantinePurgeService quarantinePurgeService = mock(QuarantinePurgeService.class);
	private final UserPagePreferenceService preferences = mock(UserPagePreferenceService.class);
	private final ConversionWebController controller = new ConversionWebController(conversionCandidateService, runner,
			conversionCommitService, quarantinePurgeService, preferences);

	private final Authentication authentication = mock(Authentication.class);

	ConversionWebControllerTest() {
		when(authentication.getName()).thenReturn("jorge");
		when(preferences.find(any(), any())).thenReturn(Map.of());
		when(conversionCandidateService.candidates(any())).thenReturn(new PageImpl<>(List.of()));
	}

	@Test
	void rendersTheScreenWithTheRecommendedOptionsOnAFirstVisit() {
		Model model = new ExtendedModelMap();

		when(conversionCommitService.quarantineRoot()).thenReturn(Optional.of(Path.of("D:", "trash")));

		Assertions.assertThat(controller.conversion(null, null, authentication, model)).isEqualTo("app/conversion");

		Assertions.assertThat(model.getAttribute("quality")).isEqualTo(ConversionQuality.BALANCED.name());
		Assertions.assertThat(model.getAttribute("audio")).isEqualTo(AudioHandling.AUTO.name());
		Assertions.assertThat(model.getAttribute("disposition")).isEqualTo(OriginalDisposition.KEEP.name());
		Assertions.assertThat(model.getAttribute("quarantineConfigured")).isEqualTo(true);
	}

	@Test
	void tellsHowLongAQuarantinedOriginalSurvivesBeforeThePurgeTakesIt() {
		Model model = new ExtendedModelMap();

		when(quarantinePurgeService.retentionDays()).thenReturn(90);

		controller.conversion(null, null, authentication, model);

		Assertions.assertThat(model.getAttribute("quarantineRetentionDays")).isEqualTo(90);
	}

	@Test
	void promisesNoDeadlineWhenNoPurgeIsScheduled() {
		Model model = new ExtendedModelMap();

		when(quarantinePurgeService.retentionDays()).thenReturn(0);

		controller.conversion(null, null, authentication, model);

		Assertions.assertThat(model.getAttribute("quarantineRetentionDays")).isEqualTo(0);
	}

	@Test
	void reopensTheScreenWithTheOptionsTheUserLastUsed() {
		Model model = new ExtendedModelMap();

		when(preferences.find("jorge", ConversionConstants.PAGE_KEY)).thenReturn(Map.of(
				ConversionConstants.QUALITY_KEY, ConversionQuality.HIGH_QUALITY.name(), ConversionConstants.AUDIO_KEY,
				AudioHandling.COPY.name(), ConversionConstants.DISPOSITION_KEY,
				OriginalDisposition.QUARANTINE.name()));

		controller.conversion(null, null, authentication, model);

		Assertions.assertThat(model.getAttribute("quality")).isEqualTo(ConversionQuality.HIGH_QUALITY.name());
		Assertions.assertThat(model.getAttribute("audio")).isEqualTo(AudioHandling.COPY.name());
		Assertions.assertThat(model.getAttribute("disposition")).isEqualTo(OriginalDisposition.QUARANTINE.name());
	}

	@Test
	void ignoresAStoredOptionThatIsNoLongerValid() {
		Model model = new ExtendedModelMap();

		when(preferences.find("jorge", ConversionConstants.PAGE_KEY))
				.thenReturn(Map.of(ConversionConstants.QUALITY_KEY, "ULTRA"));

		controller.conversion(null, null, authentication, model);

		Assertions.assertThat(model.getAttribute("quality")).isEqualTo(ConversionQuality.BALANCED.name());
	}

	@Test
	void remembersAValidPageSizeAndClampsAnythingElse() {
		Model model = new ExtendedModelMap();

		controller.conversion(null, 100, authentication, model);

		verify(preferences).save("jorge", ConversionConstants.PAGE_KEY, SharedConstants.PAGE_SIZE_KEY, "100");

		Assertions.assertThat(model.getAttribute(SharedConstants.PAGE_SIZE_KEY)).isEqualTo(100);

		Model other = new ExtendedModelMap();

		controller.conversion(-3, 999, authentication, other);

		Assertions.assertThat(other.getAttribute(SharedConstants.PAGE_SIZE_KEY)).isEqualTo(25);
		Assertions.assertThat(other.getAttribute("pageNumber")).isEqualTo(0);
	}

	@Test
	void asksForTheRequestedPage() {
		controller.conversion(2, 50, authentication, new ExtendedModelMap());

		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);

		verify(conversionCandidateService).candidates(pageable.capture());

		Assertions.assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
		Assertions.assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
	}

	@Test
	void startsTheBatchInTheBackgroundAndRemembersTheChosenOptions() {
		List<UUID> ids = List.of(UUID.randomUUID());

		when(runner.start(1)).thenReturn(true);

		controller.convert(new ConversionRequest(ids, ConversionQuality.HIGH_QUALITY, AudioHandling.AAC,
				OriginalDisposition.QUARANTINE, "_X265", NameAffixPosition.PREFIX), authentication);

		verify(runner).run(ids, new ConversionOptions(ConversionQuality.HIGH_QUALITY, AudioHandling.AAC,
				OriginalDisposition.QUARANTINE, "_X265", NameAffixPosition.PREFIX));

		verify(preferences).save("jorge", ConversionConstants.PAGE_KEY, ConversionConstants.QUALITY_KEY,
				ConversionQuality.HIGH_QUALITY.name());
		verify(preferences).save("jorge", ConversionConstants.PAGE_KEY, ConversionConstants.AUDIO_KEY,
				AudioHandling.AAC.name());
		verify(preferences).save("jorge", ConversionConstants.PAGE_KEY, ConversionConstants.DISPOSITION_KEY,
				OriginalDisposition.QUARANTINE.name());
	}

	@Test
	void fallsBackToTheRecommendedOptionsWhenTheRequestOmitsThem() {
		when(runner.start(anyInt())).thenReturn(true);

		controller.convert(new ConversionRequest(List.of(UUID.randomUUID()), null, null, null, null, null),
				authentication);

		verify(runner).run(any(), eq(ConversionOptions.defaults()));
	}

	@Test
	void doesNotStartASecondBatchWhileOneIsRunning() {
		when(runner.start(anyInt())).thenReturn(false);

		controller.convert(new ConversionRequest(List.of(UUID.randomUUID()), null, null, null, null, null),
				authentication);

		verify(runner, never()).run(any(), any());
	}

	@Test
	void releasesTheClaimWhenTheBackgroundTaskCannotBeSubmitted() {
		when(runner.start(anyInt())).thenReturn(true);
		doThrow(new TaskRejectedException("saturated")).when(runner).run(any(), any());

		controller.convert(new ConversionRequest(List.of(UUID.randomUUID()), null, null, null, null, null),
				authentication);

		verify(runner).releaseRejectedSubmission();
	}

	@Test
	void treatsAnEmptyRequestAsNothingToConvert() {
		controller.convert(new ConversionRequest(null, null, null, null, null, null), authentication);

		verify(runner).start(0);
	}

	@Test
	void storesTheOptionsAsSoonAsTheyChangeWithoutWaitingForAConversion() {
		ConversionOptions stored = controller.rememberOptions(new ConversionOptions(ConversionQuality.HIGH_QUALITY,
				AudioHandling.COPY, OriginalDisposition.QUARANTINE, "_X265", NameAffixPosition.SUFFIX), authentication);

		Assertions.assertThat(stored).isEqualTo(new ConversionOptions(ConversionQuality.HIGH_QUALITY,
				AudioHandling.COPY, OriginalDisposition.QUARANTINE, "_X265", NameAffixPosition.SUFFIX));

		verify(preferences).save("jorge", ConversionConstants.PAGE_KEY, ConversionConstants.QUALITY_KEY,
				ConversionQuality.HIGH_QUALITY.name());
		verify(preferences).save("jorge", ConversionConstants.PAGE_KEY, ConversionConstants.AUDIO_KEY,
				AudioHandling.COPY.name());
		verify(preferences).save("jorge", ConversionConstants.PAGE_KEY, ConversionConstants.DISPOSITION_KEY,
				OriginalDisposition.QUARANTINE.name());

		verify(runner, never()).run(any(), any());
	}

	@Test
	void storesTheRecommendedOptionsWhenTheRequestCarriesNone() {
		Assertions.assertThat(controller.rememberOptions(null, authentication))
				.isEqualTo(ConversionOptions.defaults());

		verify(preferences).save("jorge", ConversionConstants.PAGE_KEY, ConversionConstants.QUALITY_KEY,
				ConversionOptions.defaults().quality().name());
	}

	@Test
	void hidesTheResultWhileTheBatchIsStillRunning() {
		when(runner.isRunning()).thenReturn(true);
		when(runner.processed()).thenReturn(1);
		when(runner.total()).thenReturn(3);
		when(runner.percent()).thenReturn(45);
		when(runner.filePercent()).thenReturn(20);
		when(runner.currentFile()).thenReturn("clip.mp4");

		ConversionProgress progress = controller.progress();

		Assertions.assertThat(progress.running()).isTrue();
		Assertions.assertThat(progress.processed()).isEqualTo(1);
		Assertions.assertThat(progress.total()).isEqualTo(3);
		Assertions.assertThat(progress.percent()).isEqualTo(45);
		Assertions.assertThat(progress.filePercent()).isEqualTo(20);
		Assertions.assertThat(progress.currentFile()).isEqualTo("clip.mp4");
		Assertions.assertThat(progress.result()).isNull();
	}

	@Test
	void deliversTheReportOnceTheBatchHasFinished() {
		ConversionResult result = ConversionResult.empty("done");

		when(runner.isRunning()).thenReturn(false);
		when(runner.lastResult()).thenReturn(result);

		Assertions.assertThat(controller.progress().result()).isSameAs(result);
	}

	@Test
	void stopsTheRunningBatchAndAnswersWithTheFreshSnapshot() {
		when(runner.isRunning()).thenReturn(true);
		when(runner.processed()).thenReturn(1);
		when(runner.total()).thenReturn(3);

		ConversionProgress progress = controller.cancel();

		verify(runner).cancel();

		Assertions.assertThat(progress.running()).isTrue();
		Assertions.assertThat(progress.processed()).isEqualTo(1);
	}

	@Test
	void carriesTheEstimateIntoTheProgressSnapshot() {
		when(runner.isRunning()).thenReturn(true);
		when(runner.etaSeconds()).thenReturn(320L);

		Assertions.assertThat(controller.progress().etaSeconds()).isEqualTo(320);
	}

	@Test
	void remembersAClearedAffixAsAnExplicitChoice() {
		controller.rememberOptions(new ConversionOptions(ConversionQuality.BALANCED, AudioHandling.AUTO,
				OriginalDisposition.KEEP, "", NameAffixPosition.SUFFIX), authentication);

		// Preferences never store a blank value, so "no affix" is stored as the marker
		// the affix itself can never contain.
		verify(preferences).save("jorge", ConversionConstants.PAGE_KEY, ConversionConstants.AFFIX_KEY,
				ConversionConstants.EMPTY_AFFIX_MARKER);
	}

	@Test
	void reopensTheScreenWithAnAffixThatWasClearedOnPurpose() {
		Model model = new ExtendedModelMap();

		when(preferences.find("jorge", ConversionConstants.PAGE_KEY))
				.thenReturn(Map.of(ConversionConstants.AFFIX_KEY, ConversionConstants.EMPTY_AFFIX_MARKER));

		controller.conversion(null, null, authentication, model);

		Assertions.assertThat(model.getAttribute("nameAffix")).isEqualTo("");
	}

	@Test
	void reopensTheScreenWithTheStoredAffix() {
		Model model = new ExtendedModelMap();

		when(preferences.find("jorge", ConversionConstants.PAGE_KEY)).thenReturn(
				Map.of(ConversionConstants.AFFIX_KEY, "_X265", ConversionConstants.AFFIX_POSITION_KEY, "PREFIX"));

		controller.conversion(null, null, authentication, model);

		Assertions.assertThat(model.getAttribute("nameAffix")).isEqualTo("_X265");
		Assertions.assertThat(model.getAttribute("affixPosition")).isEqualTo(NameAffixPosition.PREFIX.name());
	}

	@Test
	void offersTheDefaultAffixOnAFirstVisit() {
		Model model = new ExtendedModelMap();

		controller.conversion(null, null, authentication, model);

		Assertions.assertThat(model.getAttribute("nameAffix")).isEqualTo(ConversionConstants.DEFAULT_NAME_AFFIX);
		Assertions.assertThat(model.getAttribute("affixPosition")).isEqualTo(NameAffixPosition.SUFFIX.name());
	}

	@Test
	void rendersTheCandidatesItWasGiven() {
		Model model = new ExtendedModelMap();

		ConversionCandidateView candidate = new ConversionCandidateView(UUID.randomUUID(), "clip.mp4", "D:\\library",
				"D:\\library\\clip.mp4", 10L, "10 B", "H264", "MP4", "1:00", "1920 × 1080", false, true, false, false,
				false, "/api/media/x/content", "bi-film", "filetype.video");

		when(conversionCandidateService.candidates(any())).thenReturn(new PageImpl<>(List.of(candidate)));

		controller.conversion(null, null, authentication, model);

		Assertions.assertThat(model.getAttribute("candidates")).isEqualTo(List.of(candidate));
		Assertions.assertThat(model.getAttribute("totalElements")).isEqualTo(1L);
	}
}