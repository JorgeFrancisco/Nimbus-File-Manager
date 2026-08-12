package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.springframework.data.domain.Page;

import br.com.jorgemelo.nimbusfilemanager.execution.application.EtaLabels;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateDeletionLauncherService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateDeletionProgressService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateExclusionService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.DuplicateService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.SimilarityLauncher;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.SimilarityViewService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.DuplicateConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.FingerprintBacklogStatus;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityView;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintBacklogProgressReader;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintBacklogLauncher;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintRunReader;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.PhashBacklogService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.VideoFingerprintBacklogService;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.web.DuplicatesWebController;
import br.com.jorgemelo.nimbusfilemanager.duplicate.infrastructure.web.VideoSimilarityWeb;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.DateSourceLabels;

/**
 * Test fixture that wires a {@link DuplicatesWebController} over mocked
 * collaborators, seeded with the neutral defaults shared by every test.
 */
public final class Fixture {

	public final DuplicateService duplicates = mock(DuplicateService.class);
	public final SimilarityViewService similarityView = mock(SimilarityViewService.class);
	public final SimilarityLauncher similarityLauncher = mock(SimilarityLauncher.class);
	public final PhashBacklogService phash = mock(PhashBacklogService.class);
	public final FingerprintBacklogLauncher fingerprintLauncher = mock(FingerprintBacklogLauncher.class);
	public final FingerprintRunReader fingerprintRunReader = mock(FingerprintRunReader.class);
	public final UserPagePreferenceService preferences = mock(UserPagePreferenceService.class);
	public final DuplicateDeletionLauncherService deletionLauncher = mock(DuplicateDeletionLauncherService.class);
	public final DuplicateDeletionProgressService deletionProgress = mock(DuplicateDeletionProgressService.class);
	public final DuplicateExclusionService exclusions = mock(DuplicateExclusionService.class);
	public final VideoFingerprintBacklogService videoBacklog = mock(VideoFingerprintBacklogService.class);

	public Fixture() {
		// Nothing pending, nothing running and nothing published: the neutral screen
		// every test starts from, so each one stubs only the state it is about.
		when(phash.status()).thenReturn(new FingerprintBacklogStatus(0, 0, 0));
		when(videoBacklog.status()).thenReturn(new FingerprintBacklogStatus(0, 0, 0));
		when(preferences.find(any(), eq(DuplicateConstants.PAGE_KEY))).thenReturn(Map.of());
		when(duplicates.candidates(any(), any())).thenReturn(Page.empty());
		when(similarityView.photos(anyInt(), any())).thenReturn(SimilarityView.none());
		when(similarityView.videos(anyInt(), any())).thenReturn(SimilarityView.none());
	}

	public DuplicatesWebController controller() {
		return new DuplicatesWebController(duplicates, phash, fingerprintLauncher, preferences, similarityView,
				similarityLauncher, deletionLauncher, deletionProgress, exclusions, videoSimilarityWeb(),
				new DateSourceLabels(), new FingerprintBacklogProgressReader(mock(EtaLabels.class), phash, videoBacklog,
						fingerprintRunReader));
	}

	private VideoSimilarityWeb videoSimilarityWeb() {
		return new VideoSimilarityWeb(videoBacklog);
	}
}