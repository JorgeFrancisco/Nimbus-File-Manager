package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityComposition;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoSignature;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaQualityRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.CompositionRow;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.MediaQuality;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.VideoFrameRawResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.VideoSimilarityProperties;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VideoSimilarityServiceTest {

	private static final String ALGORITHM = "FFMPEG_LANCZOS_PHASH_256_FRAMES_V1";
	private static final LocalDateTime NOW = LocalDateTime.parse("2026-07-08T12:00:00");

	@Mock
	private MediaFingerprintRepository mediaFingerprintRepository;

	@Mock
	private VideoSimilarityAlgorithm algorithm;

	@Mock
	private DuplicateExclusionService duplicateExclusionService;

	@Mock
	private MediaQualityRepository mediaQualityRepository;

	/**
	 * Common stubs, set up before each test so a test can override the exclusion
	 * stubs and have that override survive (building the service no longer resets
	 * them).
	 */
	@BeforeEach
	void defaults() {
		when(algorithm.kind()).thenReturn(FingerprintKind.VIDEO_PHASH);
		when(algorithm.algorithm()).thenReturn(ALGORITHM);
		when(algorithm.framesPerFingerprint()).thenReturn(5);

		when(mediaQualityRepository.findByPublicIdIn(any())).thenReturn(List.of());
		when(duplicateExclusionService.excludedFilePublicIds()).thenReturn(List.of());
		when(duplicateExclusionService.excludedFolders()).thenReturn(List.of());
		when(duplicateExclusionService.isUnderExcludedFolder(any(), any())).thenCallRealMethod();
	}

	private VideoSimilarityService service() {
		DuplicateGroupAssembler assembler = new DuplicateGroupAssembler(new DuplicateKeepPolicy(),
				mediaQualityRepository);

		return new VideoSimilarityService(mediaFingerprintRepository, assembler, algorithm, duplicateExclusionService,
				new VideoSimilarityProperties(null, null, null, null, null, null));
	}

	@Test
	void reassemblesEveryFrameOfAVideoBeforeComparing() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();

		// Two frames per video (sampleIndex 0 and 1), contiguous and ordered.
		when(mediaFingerprintRepository.findFingerprintedVideoFrames(any(), any(), any())).thenReturn(List.of(
				frameRow(first, "a", 0), frameRow(first, "a", 1), frameRow(second, "b", 0), frameRow(second, "b", 1)));
		when(algorithm.candidateBuckets(any())).thenReturn(Set.of(1L));
		when(algorithm.similarityPercent(any(VideoSignature.class), any(VideoSignature.class), eq(90)))
				.thenAnswer(invocation -> invocation.<VideoSignature>getArgument(0).frames().size() == 2
						&& invocation.<VideoSignature>getArgument(1).frames().size() == 2 ? 92 : -1);

		List<AnalyzedGroup> groups = service().analyze(90, (_, _) -> {
		}).groups();

		assertThat(groups).hasSize(1);
		assertThat(groups.getFirst().similarityPercent()).isEqualTo(92);
	}

	@Test
	void excludedFileNeverForcesAGroup() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();

		when(mediaFingerprintRepository.findFingerprintedVideoFrames(any(), any(), any()))
				.thenReturn(List.of(row(first, "a"), row(second, "b")));
		// The two would group (similar), but excluding one leaves a single candidate.
		when(algorithm.candidateBuckets(any())).thenReturn(Set.of(1L));
		when(algorithm.similarityPercent(any(), any(), eq(90))).thenReturn(95);
		when(duplicateExclusionService.excludedFilePublicIds()).thenReturn(List.of(first));

		assertThat(service().analyze(90, (_, _) -> {
		}).groups()).isEmpty();
	}

	@Test
	void excludedFolderDropsVideosUnderIt() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();

		when(mediaFingerprintRepository.findFingerprintedVideoFrames(any(), any(), any()))
				.thenReturn(List.of(row(first, "a"), row(second, "b")));
		// The two would group (similar), but both sit under the excluded folder "C:/",
		// so isUnderExcludedFolder drops both before any comparison.
		when(algorithm.candidateBuckets(any())).thenReturn(Set.of(1L));
		when(algorithm.similarityPercent(any(), any(), eq(90))).thenReturn(95);
		when(duplicateExclusionService.excludedFolders()).thenReturn(List.of("C:/"));

		assertThat(service().analyze(90, (_, _) -> {
		}).groups()).isEmpty();
	}

	@Test
	void groupOfThreeReportsTheLowestPairwiseSimilarityAsTheFloor() {
		when(mediaFingerprintRepository.findFingerprintedVideoFrames(any(), any(), any())).thenReturn(
				List.of(row(UUID.randomUUID(), "a"), row(UUID.randomUUID(), "b"), row(UUID.randomUUID(), "c")));
		when(algorithm.candidateBuckets(any())).thenReturn(Set.of(1L));
		when(algorithm.similarityPercent(any(), any(), eq(90))).thenReturn(95, 92, 90);

		List<AnalyzedGroup> groups = service().analyze(90, (_, _) -> {
		}).groups();

		assertThat(groups).hasSize(1);
		assertThat(groups.getFirst().members()).hasSize(3);
		assertThat(groups.getFirst().similarityPercent()).isEqualTo(90);
	}

	@Test
	void aCandidateWithNullFolderSurvivesFolderExclusion() {
		VideoFrameRawResponse nullFolder = new VideoFrameRawResponse(UUID.randomUUID(), 0, 0L, new byte[32],
				new byte[1024], "a", "mp4", 1000L, "C:/a.mp4", null, NOW, 10.0, 1920, 1080);

		when(mediaFingerprintRepository.findFingerprintedVideoFrames(any(), any(), any()))
				.thenReturn(List.of(nullFolder));
		when(duplicateExclusionService.excludedFolders()).thenReturn(List.of("C:/x"));

		// A single surviving candidate forms no group, but the null folder must not
		// throw and must not be dropped by the folder filter.
		assertThat(service().analyze(90, (_, _) -> {
		}).groups()).isEmpty();
	}

	private VideoFrameRawResponse row(UUID id, String name) {
		return frameRow(id, name, 0);
	}

	private MediaQuality quality(UUID id, LocalDateTime capturedAt) {
		return new MediaQuality(id, 1920, 1080, capturedAt, true, MediaSubcategory.CAMERA, DateSource.EXIF, true);
	}

	private VideoFrameRawResponse sizedRow(UUID id, String name, long sizeBytes) {
		return new VideoFrameRawResponse(id, 0, 0L, new byte[32], new byte[1024], name, "mp4", sizeBytes,
				"C:/" + name + ".mp4", "C:/", NOW, 10.0, 1920, 1080);
	}

	private VideoFrameRawResponse frameRow(UUID id, String name, int sampleIndex) {
		return new VideoFrameRawResponse(id, sampleIndex, sampleIndex * 1000L, new byte[32], new byte[1024], name,
				"mp4", 1000L, "C:/" + name + ".mp4", "C:/", NOW, 10.0, 1920, 1080);
	}

	@Test
	void groupsVideosThatShareABucketAndPassTheThreshold() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();

		when(mediaFingerprintRepository.findFingerprintedVideoFrames(any(), any(), any()))
				.thenReturn(List.of(row(first, "a"), row(second, "b")));
		when(algorithm.candidateBuckets(any())).thenReturn(Set.of(1L));
		when(algorithm.similarityPercent(any(), any(), eq(90))).thenReturn(95);

		List<AnalyzedGroup> groups = service().analyze(90, (_, _) -> {
		}).groups();

		assertThat(groups).hasSize(1);
		assertThat(groups.getFirst().members()).hasSize(2);
		assertThat(groups.getFirst().similarityPercent()).isEqualTo(95);
	}

	@SuppressWarnings("unchecked")
	@Test
	void theGroupThatWastesMostComesFirst() {
		UUID smallA = UUID.randomUUID();
		UUID smallB = UUID.randomUUID();
		UUID bigA = UUID.randomUUID();
		UUID bigB = UUID.randomUUID();

		// Two pairs, each in its own bucket. Their order in the published result is
		// decided here, once, so paginating it later cannot reshuffle what page two
		// means - and the pair that frees the most bytes is the one worth seeing first.
		when(mediaFingerprintRepository.findFingerprintedVideoFrames(any(), any(), any()))
				.thenReturn(List.of(sizedRow(smallA, "small-a", 1_000L), sizedRow(smallB, "small-b", 1_000L),
						sizedRow(bigA, "big-a", 900_000L), sizedRow(bigB, "big-b", 900_000L)));
		when(algorithm.candidateBuckets(any(VideoSignature.class))).thenReturn(Set.of(1L), Set.of(1L), Set.of(2L),
				Set.of(2L));
		when(algorithm.similarityPercent(any(), any(), eq(90))).thenReturn(95);

		when(mediaQualityRepository.findByPublicIdIn(any())).thenReturn(List.of(quality(smallA, NOW.minusDays(1)),
				quality(smallB, NOW), quality(bigA, NOW.minusDays(1)), quality(bigB, NOW)));

		List<AnalyzedGroup> groups = service().analyze(90, (_, _) -> {
		}).groups();

		assertThat(groups).hasSize(2);
		assertThat(groups).extracting(AnalyzedGroup::members).allSatisfy(members -> assertThat(members).hasSize(2));

		// The order is decided here and frozen: whatever each group frees, the one that
		// frees most is first. Paginating the published result later cannot reshuffle
		// what page two means.
		assertThat(groups).extracting(AnalyzedGroup::wastedBytes).isSortedAccordingTo(Comparator.reverseOrder());
	}

	@SuppressWarnings("unchecked")
	@Test
	void doesNotCompareVideosInDisjointBuckets() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();

		when(mediaFingerprintRepository.findFingerprintedVideoFrames(any(), any(), any()))
				.thenReturn(List.of(row(first, "a"), row(second, "b")));
		// Distinct, disjoint buckets for the two videos (first call -> {1}, second ->
		// {2}).
		when(algorithm.candidateBuckets(any(VideoSignature.class))).thenReturn(Set.of(1L), Set.of(2L));

		List<AnalyzedGroup> groups = service().analyze(90, (_, _) -> {
		}).groups();

		// Disjoint-bucket pairs never reach the expensive comparison, and never group.
		verify(algorithm, never()).similarityPercent(any(), any(), anyInt());
		assertThat(groups).isEmpty();
	}

	@Test
	void theFamilyAndTheCompositionDescribeTheVideoAnalysis() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();

		when(mediaFingerprintRepository.countEligibleForSimilarity(any(), any())).thenReturn(7);
		when(mediaFingerprintRepository.findVideoCompositionRows(any(), any(), any()))
				.thenReturn(List.of(new CompositionRow(first, "C:/Videos"), new CompositionRow(second, "C:/Videos")));

		VideoSimilarityService service = service();

		assertThat(service.mediaType()).isEqualTo(FileType.VIDEO);
		assertThat(service.family(70).mediaType()).isEqualTo(FileType.VIDEO);
		assertThat(service.family(70).parametersDigest()).hasSize(64);

		SimilarityComposition composition = service.composition();

		assertThat(composition.eligibleCount()).isEqualTo(7);
		assertThat(composition.analyzedCount()).isEqualTo(2);
		assertThat(composition.digest()).hasSize(64);
		assertThat(composition.coverageComplete()).isFalse();
	}
}