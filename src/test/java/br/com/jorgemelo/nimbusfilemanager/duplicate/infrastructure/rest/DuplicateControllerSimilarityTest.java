package br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.EtaEstimate;
import br.com.jorgemelo.nimbusfilemanager.execution.application.EtaLabels;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.FingerprintFailureLabels;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.SimilarityLauncher;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.SimilarityViewService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedMember;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogProgress;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.PublishedGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.PublishedMember;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityFreshness;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityGroupResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityMemberResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityView;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintBacklogProgressReader;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintRunReader;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.PhashBacklogService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.VideoFingerprintBacklogService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Reason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Verdict;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.SimilarityMemberFile;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.PagedResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.ExecutionType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.Execution;

/**
 * The public contract of the similarity endpoints, which is what a consumer
 * outside this codebase depends on: an answer that exists is 200 with the
 * decision spelled out per file, and an answer that does not exist yet is 202
 * pointing at the execution that will produce it - never a synchronous
 * computation and never an empty 200 that reads as "no duplicates".
 */
class DuplicateControllerSimilarityTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, Month.MAY, 1, 10, 0);
	/** When the file was last written to, which is a moment on the timeline. */
	private static final Instant WRITTEN_AT = Instant.parse("2026-05-01T10:00:00Z");

	private final DuplicateService duplicateService = mock(DuplicateService.class);
	private final SimilarityViewService similarityViewService = mock(SimilarityViewService.class);
	private final SimilarityLauncher similarityLauncher = mock(SimilarityLauncher.class);
	private final PhashBacklogService phashBacklogService = mock(PhashBacklogService.class);
	private final VideoFingerprintBacklogService videoFingerprintBacklogService = mock(
			VideoFingerprintBacklogService.class);

	private final FingerprintRunReader fingerprintRunReader = mock(FingerprintRunReader.class);

	private final DuplicateController controller = new DuplicateController(duplicateService, similarityViewService,
			similarityLauncher, phashBacklogService, videoFingerprintBacklogService, new FingerprintFailureLabels(),
			new FingerprintBacklogProgressReader(mock(EtaLabels.class), phashBacklogService,
					videoFingerprintBacklogService, fingerprintRunReader));

	@Test
	void anAnalysisThatWasNeverRunIsQueuedAndAnsweredWithWhereToFollowIt() {
		UUID publicId = UUID.randomUUID();

		when(similarityViewService.photos(anyInt(), any())).thenReturn(unpublished());
		when(similarityLauncher.launchPhotos(anyInt()))
				.thenReturn(Execution.builder().executionPublicId(publicId).build());

		ResponseEntity<PagedResponse<SimilarityGroupResponse>> response = controller.similarPhotos(70,
				PageRequest.of(0, 20));

		Assertions.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		Assertions.assertThat(response.getHeaders().getFirst("Location"))
				.isEqualTo("/api/executions/" + publicId);
		Assertions.assertThat(response.getBody()).isNull();
	}

	/**
	 * The screen polls this while fingerprints are being computed, and what it
	 * shows is three things at once: how far the backlog has got, whether anything
	 * is working on it, and how long is left - the last of which only means
	 * anything while something is running.
	 */
	@Test
	void thePhotoBacklogAnswersHowFarItHasGotAndWhetherAnythingIsWorkingOnIt() {
		when(phashBacklogService.status()).thenReturn(new FingerprintBacklogStatus(30, 70, 0));
		when(fingerprintRunReader.isRunning(ExecutionType.FINGERPRINT_PHOTO)).thenReturn(true);
		when(fingerprintRunReader.eta(ExecutionType.FINGERPRINT_PHOTO)).thenReturn(EtaEstimate.of(90));

		FingerprintBacklogProgress progress = controller.photoBacklog();

		Assertions.assertThat(progress).extracting(FingerprintBacklogProgress::pending,
				FingerprintBacklogProgress::done, FingerprintBacklogProgress::failed,
				FingerprintBacklogProgress::total, FingerprintBacklogProgress::running)
				.containsExactly(30L, 70L, 0L, 100L, true);
		Assertions.assertThat(progress.eta()).as("the fact travels, and the client words it")
				.isEqualTo(EtaEstimate.of(90));
	}

	@Test
	void theVideoBacklogAnswersForItsOwnRunAndNotForThePhotoOne() {
		when(videoFingerprintBacklogService.status()).thenReturn(new FingerprintBacklogStatus(5, 5, 1));
		when(fingerprintRunReader.isRunning(ExecutionType.FINGERPRINT_VIDEO)).thenReturn(false);

		FingerprintBacklogProgress progress = controller.videoBacklog();

		Assertions.assertThat(progress).extracting(FingerprintBacklogProgress::pending,
				FingerprintBacklogProgress::failed, FingerprintBacklogProgress::running)
				.containsExactly(5L, 1L, false);
	}

	@Test
	void aPublishedAnalysisIsReturnedWithTheDecisionAlreadyMadeForEachFile() {
		when(similarityViewService.photos(anyInt(), any())).thenReturn(published());

		ResponseEntity<PagedResponse<SimilarityGroupResponse>> response = controller.similarPhotos(70,
				PageRequest.of(0, 20));

		Assertions.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

		SimilarityGroupResponse group = response.getBody().content().getFirst();

		Assertions.assertThat(group.groupId()).isEqualTo("7");
		Assertions.assertThat(group.similarityPercent()).isEqualTo(96);
		Assertions.assertThat(group.wastedBytes()).isEqualTo(2048L);
		Assertions.assertThat(group.outdated()).isFalse();

		Assertions.assertThat(group.members()).extracting(SimilarityMemberResponse::verdict).containsExactly("KEEP",
				"DELETE_CANDIDATE");
		// A member the analysis reached no reason for reports none, rather than a label
		// the screen would have to translate back.
		Assertions.assertThat(group.members()).extracting(SimilarityMemberResponse::reason)
				.containsExactly("ORIGINAL", null);
		Assertions.assertThat(group.members()).extracting(SimilarityMemberResponse::actionable).containsExactly(true,
				false);

		verify(similarityLauncher, never()).launchPhotos(anyInt());
	}

	@Test
	void aMemberWhoseFileIsGoneIsReportedWithoutAPathAndNotActionable() {
		when(similarityViewService.photos(anyInt(), any())).thenReturn(published());

		SimilarityGroupResponse group = controller.similarPhotos(70, PageRequest.of(0, 20)).getBody().content()
				.getFirst();

		Assertions.assertThat(group.members().get(1).path()).isNull();
		Assertions.assertThat(group.members().get(1).fileName()).isNull();
		Assertions.assertThat(group.members().get(1).sizeBytes()).isNull();
		Assertions.assertThat(group.members().get(1).actionable()).isFalse();
	}

	/**
	 * What the screen asks after it is already on screen. The verdict is the same
	 * rule the API applies before answering - asked here on its own, because this
	 * is the one thing the page could not afford to wait for.
	 */
	@Test
	void theFreshnessOfAPublishedPhotoAnalysisIsAnsweredOnItsOwn() {
		when(similarityViewService.outdated(eq(ExecutionType.SIMILARITY_PHOTO), anyInt()))
				.thenReturn(Optional.of(true));

		Assertions.assertThat(controller.similarPhotoFreshness(70))
				.isEqualTo(new SimilarityFreshness(true, true));
	}

	@Test
	void aPhotoAnalysisThatStillDescribesTheLibrarySaysSo() {
		when(similarityViewService.outdated(eq(ExecutionType.SIMILARITY_PHOTO), anyInt()))
				.thenReturn(Optional.of(false));

		Assertions.assertThat(controller.similarPhotoFreshness(70))
				.isEqualTo(new SimilarityFreshness(true, false));
	}

	/** Never analysed is not "current": the answer says there is no answer. */
	@Test
	void aFamilyWithNoPublishedAnalysisIsNeitherCurrentNorOutdated() {
		when(similarityViewService.outdated(any(), anyInt())).thenReturn(Optional.empty());

		Assertions.assertThat(controller.similarPhotoFreshness(70).published()).isFalse();
		Assertions.assertThat(controller.similarVideoFreshness(70).published()).isFalse();
	}

	@Test
	void theFreshnessOfAPublishedVideoAnalysisIsAnsweredOnItsOwn() {
		when(similarityViewService.outdated(eq(ExecutionType.SIMILARITY_VIDEO), anyInt()))
				.thenReturn(Optional.of(true));

		Assertions.assertThat(controller.similarVideoFreshness(70))
				.isEqualTo(new SimilarityFreshness(true, true));
	}

	/** A pair no rule produces, refused where it would be built. */
	@Test
	void nothingPublishedCanNeverClaimToBeOutdated() {
		Assertions.assertThatThrownBy(() -> new SimilarityFreshness(false, true))
				.isInstanceOf(IllegalArgumentException.class);
	}

	/**
	 * The rule the whole durable model exists for: a photo arriving does not take
	 * the analysis off the screen. It stays, and the screen says there is something
	 * newer to be found.
	 */
	@Test
	void anAnalysisTheLibraryHasMovedPastIsStillReturnedAndSaysSo() {
		when(similarityViewService.photos(anyInt(), any())).thenReturn(published());
		when(similarityViewService.outdated(eq(ExecutionType.SIMILARITY_PHOTO), anyInt()))
				.thenReturn(Optional.of(true));

		ResponseEntity<PagedResponse<SimilarityGroupResponse>> response = controller.similarPhotos(70,
				PageRequest.of(0, 20));

		Assertions.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		Assertions.assertThat(response.getBody().content().getFirst().outdated()).isTrue();
	}

	@Test
	void theVideosEndpointQueuesTheVideoAnalysis() {
		when(similarityViewService.videos(anyInt(), any())).thenReturn(unpublished());
		when(similarityLauncher.launchVideos(anyInt()))
				.thenReturn(Execution.builder().executionPublicId(UUID.randomUUID()).build());

		Assertions.assertThat(controller.similarVideos(null, PageRequest.of(0, 20)).getStatusCode())
				.isEqualTo(HttpStatus.ACCEPTED);

		verify(similarityLauncher).launchVideos(anyInt());
		verify(similarityLauncher, never()).launchPhotos(anyInt());
	}

	private SimilarityView unpublished() {
		return new SimilarityView(Page.empty(), false, false, 10, 0, 8000, false);
	}

	private SimilarityView published() {
		PublishedMember keep = new PublishedMember(new AnalyzedMember(UUID.randomUUID(), Verdict.KEEP, Reason.ORIGINAL),
				new SimilarityMemberFile(UUID.randomUUID(), "original.jpg", "jpg", "PHOTO", 1024L,
						"C:/fotos/original.jpg", "C:/fotos", WRITTEN_AT, 1920, 1080, NOW, DateSource.EXIF,
						LifecycleStatus.ACTIVE),
				true);

		PublishedMember gone = new PublishedMember(
				new AnalyzedMember(UUID.randomUUID(), Verdict.DELETE_CANDIDATE, null), null, false);

		return new SimilarityView(new PageImpl<>(List.of(new PublishedGroup("7", 96, 2048L, List.of(keep, gone), 1))),
				true, false, 10, 10, 8000, true);
	}
}