package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Page;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateDeletionAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateExclusionService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.PhotoSimilarityAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.PhotoSimilarityService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.VideoSimilarityAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.VideoSimilarityService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.PhashBacklogAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.PhashBacklogService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.VideoFingerprintBacklogAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.VideoFingerprintBacklogService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.web.DuplicatesWebController;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.web.VideoSimilarityWeb;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;

/**
 * Test fixture that wires a {@link DuplicatesWebController} over mocked
 * collaborators, seeded with the neutral defaults shared by every test.
 */
public final class Fixture {

	public final DuplicateService duplicates = mock(DuplicateService.class);
	public final PhotoSimilarityService similarity = mock(PhotoSimilarityService.class);
	private final PhashBacklogService phash = mock(PhashBacklogService.class);
	public final PhashBacklogAsyncRunner phashRunner = mock(PhashBacklogAsyncRunner.class);
	public final UserPagePreferenceService preferences = mock(UserPagePreferenceService.class);
	private final PhotoSimilarityAsyncRunner similarityRunner = mock(PhotoSimilarityAsyncRunner.class);
	public final DuplicateDeletionAsyncRunner deletionRunner = mock(DuplicateDeletionAsyncRunner.class);
	public final DuplicateExclusionService exclusions = mock(DuplicateExclusionService.class);
	public final VideoSimilarityService videoSimilarity = mock(VideoSimilarityService.class);
	public final VideoSimilarityAsyncRunner videoSimilarityRunner = mock(VideoSimilarityAsyncRunner.class);
	public final VideoFingerprintBacklogService videoBacklog = mock(VideoFingerprintBacklogService.class);
	public final VideoFingerprintBacklogAsyncRunner videoBacklogRunner = mock(VideoFingerprintBacklogAsyncRunner.class);

	public Fixture() {
		// The screens read progress from the runners' live status, which counts what a
		// run has finished before its batch is written; the services only answer for
		// what is already stored.
		when(phash.status()).thenReturn(new FingerprintBacklogStatus(0, 0, 0));
		when(videoBacklog.status()).thenReturn(new FingerprintBacklogStatus(0, 0, 0));
		when(phash.status()).thenReturn(new FingerprintBacklogStatus(0, 0, 0));
		when(videoBacklog.status()).thenReturn(new FingerprintBacklogStatus(0, 0, 0));
		when(preferences.find(any(), eq(DuplicateConstants.PAGE_KEY))).thenReturn(Map.of());
		when(duplicates.candidates(any(), any())).thenReturn(Page.empty());
		when(similarity.cachedPage(anyInt(), any())).thenReturn(Optional.of(Page.empty()));
		when(videoSimilarity.cachedPage(anyInt(), any())).thenReturn(Optional.of(Page.empty()));
	}

	public DuplicatesWebController controller() {
		return new DuplicatesWebController(duplicates, similarity, phash, phashRunner, preferences,
				similarityRunner, deletionRunner, exclusions, videoSimilarityWeb());
	}

	private VideoSimilarityWeb videoSimilarityWeb() {
		return new VideoSimilarityWeb(videoSimilarity, videoSimilarityRunner, videoBacklog, videoBacklogRunner);
	}
}