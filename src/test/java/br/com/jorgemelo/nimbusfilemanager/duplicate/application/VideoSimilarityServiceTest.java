package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarVideoGroupResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.VideoSignature;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintKind;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaQualityRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.VideoFrameRawResponse;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.VideoSimilarityProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Api;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;

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
	private AppSettingService appSettingService;

	@Mock
	private NimbusFileManagerProperties properties;

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

		List<Object[]> signatureRows = new ArrayList<>();

		signatureRows.add(new Object[] { 2L, 2L, NOW });

		when(mediaFingerprintRepository.fingerprintSignature(any(), any())).thenReturn(signatureRows);
		when(mediaQualityRepository.findByPublicIdIn(any())).thenReturn(List.of());
		when(duplicateExclusionService.excludedFilePublicIds()).thenReturn(List.of());
		when(duplicateExclusionService.excludedFolders()).thenReturn(List.of());
		when(duplicateExclusionService.isUnderExcludedFolder(any(), any())).thenCallRealMethod();
		when(appSettingService.intValue(any(), anyInt())).thenReturn(500);
		when(properties.api()).thenReturn(new Api(500, 20, 500));
	}

	private VideoSimilarityService service() {
		DuplicateGroupAssembler assembler = new DuplicateGroupAssembler(new DuplicateKeepPolicy(),
				mediaQualityRepository);

		return new VideoSimilarityService(mediaFingerprintRepository, assembler, algorithm, appSettingService,
				properties, duplicateExclusionService,
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

		Page<SimilarVideoGroupResponse> groups = service().groups(90, PageRequest.of(0, 20));

		assertThat(groups.getContent()).hasSize(1);
		assertThat(groups.getContent().getFirst().similarityPercent()).isEqualTo(92);
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

		assertThat(service().groups(90, PageRequest.of(0, 20)).getContent()).isEmpty();
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

		assertThat(service().groups(90, PageRequest.of(0, 20)).getContent()).isEmpty();
	}

	@Test
	void cachesGroupingAndInvalidatesOnDemand() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();

		when(mediaFingerprintRepository.findFingerprintedVideoFrames(any(), any(), any()))
				.thenReturn(List.of(row(first, "a"), row(second, "b")));
		when(algorithm.candidateBuckets(any())).thenReturn(Set.of(1L));
		when(algorithm.similarityPercent(any(), any(), eq(90))).thenReturn(95);

		VideoSimilarityService service = service();

		assertThat(service.cachedPage(90, PageRequest.of(0, 20))).isEmpty();

		service.groups(90, PageRequest.of(0, 20));

		assertThat(service.isCached(90)).isTrue();
		assertThat(service.cachedPage(90, PageRequest.of(0, 20))).isPresent();

		service.invalidateCache();

		assertThat(service.isCached(90)).isFalse();
	}

	@Test
	void evictFromCacheDropsGroupsThatLostAMember() {
		UUID keep = UUID.randomUUID();
		UUID other = UUID.randomUUID();

		when(mediaFingerprintRepository.findFingerprintedVideoFrames(any(), any(), any()))
				.thenReturn(List.of(row(keep, "a"), row(other, "b")));
		when(algorithm.candidateBuckets(any())).thenReturn(Set.of(1L));
		when(algorithm.similarityPercent(any(), any(), eq(90))).thenReturn(95);

		VideoSimilarityService service = service();

		SimilarVideoGroupResponse group = service.groups(90, PageRequest.of(0, 20)).getContent().getFirst();

		service.evictFromCache(List.of(UUID.fromString(group.keep().id().toString())));

		assertThat(service.cachedPage(90, PageRequest.of(0, 20))).get()
				.satisfies(page -> assertThat(page.getContent()).isEmpty());
	}

	@Test
	void groupOfThreeReportsTheLowestPairwiseSimilarityAsTheFloor() {
		when(mediaFingerprintRepository.findFingerprintedVideoFrames(any(), any(), any())).thenReturn(
				List.of(row(UUID.randomUUID(), "a"), row(UUID.randomUUID(), "b"), row(UUID.randomUUID(), "c")));
		when(algorithm.candidateBuckets(any())).thenReturn(Set.of(1L));
		when(algorithm.similarityPercent(any(), any(), eq(90))).thenReturn(95, 92, 90);

		Page<SimilarVideoGroupResponse> groups = service().groups(90, PageRequest.of(0, 20));

		assertThat(groups.getContent()).hasSize(1);
		assertThat(groups.getContent().getFirst().files()).isEqualTo(3);
		assertThat(groups.getContent().getFirst().similarityPercent()).isEqualTo(90);
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
		assertThat(service().groups(90, PageRequest.of(0, 20)).getContent()).isEmpty();
	}

	@Test
	void clampsRequestedSimilarityToItsBounds() {
		when(mediaFingerprintRepository.findFingerprintedVideoFrames(any(), any(), any())).thenReturn(List.of());

		VideoSimilarityService service = service();

		service.groups(null, PageRequest.of(0, 20));
		service.groups(200, PageRequest.of(0, 20));
		service.groups(50, PageRequest.of(0, 20));

		assertThat(service.isCached(70)).isTrue();
		assertThat(service.isCached(100)).isTrue();
	}

	@Test
	void evictFromCacheIgnoresNullOrEmptyInput() {
		VideoSimilarityService service = service();

		service.evictFromCache(null);
		service.evictFromCache(List.of());

		assertThat(service.isCached(90)).isFalse();
	}

	private VideoFrameRawResponse row(UUID id, String name) {
		return frameRow(id, name, 0);
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

		Page<SimilarVideoGroupResponse> groups = service().groups(90, PageRequest.of(0, 20));

		assertThat(groups.getContent()).hasSize(1);
		assertThat(groups.getContent().getFirst().files()).isEqualTo(2);
		assertThat(groups.getContent().getFirst().similarityPercent()).isEqualTo(95);
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

		Page<SimilarVideoGroupResponse> groups = service().groups(90, PageRequest.of(0, 20));

		// Disjoint-bucket pairs never reach the expensive comparison, and never group.
		verify(algorithm, never()).similarityPercent(any(), any(), anyInt());
		assertThat(groups.getContent()).isEmpty();
	}
}