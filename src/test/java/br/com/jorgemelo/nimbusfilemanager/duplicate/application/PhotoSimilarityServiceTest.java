package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarPhotoGroupResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaQualityRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.MediaQuality;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PhotoHashRawResponse;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Api;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

class PhotoSimilarityServiceTest {

	private final MediaFingerprintRepository repository = mock(MediaFingerprintRepository.class);
	private final AppSettingService settings = mock(AppSettingService.class);
	private final MediaQualityRepository mediaQualityRepository = mock(MediaQualityRepository.class);
	private final DuplicateExclusionService exclusions = mock(DuplicateExclusionService.class);

	@Test
	void identicalSsimSamplesGroupAndDisplayOneHundredPercent() {
		LocalDateTime older = LocalDateTime.of(2023, Month.JANUARY, 1, 10, 0);
		LocalDateTime newer = LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0);

		when(repository.findFingerprintedPhotos(any(), any(), any()))
				.thenReturn(new PageImpl<>(List.of(photo(1L, hash(0), sample(100), older),
						photo(2L, hash(0), sample(100), newer), photo(3L, hash(255), sample(100), older))));

		UUID id1 = UuidV7.fromLegacy(1L);
		UUID id2 = UuidV7.fromLegacy(2L);
		UUID id3 = UuidV7.fromLegacy(3L);

		when(mediaQualityRepository.findByPublicIdIn(any())).thenReturn(
				List.of(new MediaQuality(id1, 100, 100, older, true, MediaSubcategory.CAMERA, DateSource.EXIF, true),
						new MediaQuality(id2, 100, 100, newer, true, MediaSubcategory.CAMERA, DateSource.EXIF, true),
						new MediaQuality(id3, 100, 100, older, true, MediaSubcategory.CAMERA, DateSource.EXIF, true)));

		Page<SimilarPhotoGroupResponse> result = service(new PhotoSsimService()).groups(70, PageRequest.of(0, 20));

		Assertions.assertThat(result.getTotalElements()).isEqualTo(1);

		SimilarPhotoGroupResponse group = result.getContent().getFirst();

		Assertions.assertThat(group.files()).isEqualTo(2);
		Assertions.assertThat(group.similarityPercent()).isEqualTo(100);
		Assertions.assertThat(group.keep().id()).isEqualTo(UuidV7.fromLegacy(1L));
	}

	@Test
	void excludedFileIsDroppedBeforeGroupingSoItsGroupNeverForms() {
		LocalDateTime when = LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0);

		when(repository.findFingerprintedPhotos(any(), any(), any())).thenReturn(
				new PageImpl<>(List.of(photo(1L, hash(0), sample(100), when), photo(2L, hash(0), sample(100), when))));
		when(exclusions.excludedFilePublicIds()).thenReturn(List.of(UuidV7.fromLegacy(2L)));

		Page<SimilarPhotoGroupResponse> result = service(new PhotoSsimService()).groups(70, PageRequest.of(0, 20));

		// Only photo 1 survives the exclusion filter, so the identical pair can never
		// group.
		Assertions.assertThat(result.getTotalElements()).isZero();
	}

	@Test
	void excludedFolderDropsTheFolderAndItsSubfoldersButKeepsUnrelatedPhotos() {
		LocalDateTime when = LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0);

		// Two identical photos in an excluded folder and its subfolder (must be
		// dropped),
		// plus an identical pair in an unrelated folder (must still group).
		when(repository.findFingerprintedPhotos(any(), any(), any()))
				.thenReturn(new PageImpl<>(List.of(photoIn(1L, "C:/Fotos", hash(0), sample(100), when),
						photoIn(2L, "C:/Fotos/sub", hash(0), sample(100), when),
						photoIn(3L, "C:/Other", hash(0), sample(100), when),
						photoIn(4L, "C:/Other", hash(0), sample(100), when))));
		when(exclusions.excludedFolders()).thenReturn(List.of("C:/Fotos"));
		when(exclusions.isUnderExcludedFolder(any(), any())).thenCallRealMethod();

		Page<SimilarPhotoGroupResponse> result = service(new PhotoSsimService()).groups(70, PageRequest.of(0, 20));

		Assertions.assertThat(result.getTotalElements()).isEqualTo(1);
		Assertions.assertThat(result.getContent().getFirst().files()).isEqualTo(2);
	}

	@Test
	void invalidateCacheForcesTheNextLoadToRecompute() {
		when(repository.findFingerprintedPhotos(any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

		PhotoSimilarityService service = service(new PhotoSsimService());

		service.groups(70, PageRequest.of(0, 20));
		Assertions.assertThat(service.isCached(70)).isTrue();

		service.invalidateCache();

		Assertions.assertThat(service.isCached(70)).isFalse();
	}

	@Test
	void identicalPHashDoesNotOverrideLowSsim() {
		when(repository.findFingerprintedPhotos(any(), any(), any()))
				.thenReturn(new PageImpl<>(List.of(photo(1L, hash(0), sample(0), LocalDateTime.now()),
						photo(2L, hash(0), sample(255), LocalDateTime.now()))));

		Assertions.assertThat(service(new PhotoSsimService()).groups(70, PageRequest.of(0, 20))).isEmpty();
	}

	@Test
	void requestBelowFloorIsClampedUsingSsim() {
		PhotoSsimService ssim = mock(PhotoSsimService.class);

		when(ssim.similarityPercent(any(), any())).thenReturn(60);
		when(repository.findFingerprintedPhotos(any(), any(), any()))
				.thenReturn(new PageImpl<>(List.of(photo(1L, hash(0), sample(1), LocalDateTime.now()),
						photo(2L, hash(0), sample(2), LocalDateTime.now()))));

		Assertions.assertThat(service(ssim).groups(50, PageRequest.of(0, 20))).isEmpty();
	}

	@Test
	void requestAboveCeilingIsClampedToExactSsimOnly() {
		when(repository.findFingerprintedPhotos(any(), any(), any()))
				.thenReturn(new PageImpl<>(List.of(photo(1L, hash(0), sample(100), LocalDateTime.now()),
						photo(2L, hash(0), sample(100), LocalDateTime.now()))));

		Assertions.assertThat(service(new PhotoSsimService()).groups(150, PageRequest.of(0, 20)).getTotalElements())
				.isEqualTo(1);
	}

	@Test
	void pHashBeyondCandidateRadiusIsRejectedBeforeSsim() {
		when(repository.findFingerprintedPhotos(any(), any(), any()))
				.thenReturn(new PageImpl<>(List.of(photo(1L, hash(0), sample(100), LocalDateTime.now()),
						photo(2L, hash(255), sample(100), LocalDateTime.now()))));

		Assertions.assertThat(service(new PhotoSsimService()).groups(70, PageRequest.of(0, 20))).isEmpty();
	}

	@Test
	void cliqueGroupingDoesNotChainPairsBelowSsimThreshold() {
		PhotoSsimService ssim = mock(PhotoSsimService.class);

		when(ssim.similarityPercent(any(), any())).thenAnswer(invocation -> {
			int first = ((byte[]) invocation.getArgument(0))[0] & 0xFF;
			int second = ((byte[]) invocation.getArgument(1))[0] & 0xFF;
			return Math.abs(first - second) <= 1 ? 90 : 80;
		});
		when(repository.findFingerprintedPhotos(any(), any(), any()))
				.thenReturn(new PageImpl<>(List.of(photo(1L, hash(0), sample(1), LocalDateTime.now()),
						photo(2L, hash(0), sample(2), LocalDateTime.now()),
						photo(3L, hash(0), sample(3), LocalDateTime.now()))));

		Page<SimilarPhotoGroupResponse> result = service(ssim).groups(90, PageRequest.of(0, 20));

		Assertions.assertThat(result.getTotalElements()).isEqualTo(1);
		Assertions.assertThat(result.getContent().getFirst().files()).isEqualTo(2);
	}

	@Test
	void groupsArePaginatedAfterSsimConfirmation() {
		when(repository.findFingerprintedPhotos(any(), any(), any()))
				.thenReturn(new PageImpl<>(List.of(photo(1L, hash(0), sample(10), LocalDateTime.now()),
						photo(2L, hash(0), sample(10), LocalDateTime.now()),
						photo(3L, hash(255), sample(20), LocalDateTime.now()),
						photo(4L, hash(255), sample(20), LocalDateTime.now()))));

		Page<SimilarPhotoGroupResponse> result = service(new PhotoSsimService()).groups(70, PageRequest.of(0, 1));

		Assertions.assertThat(result.getContent()).hasSize(1);
		Assertions.assertThat(result.getTotalElements()).isEqualTo(2);
		Assertions.assertThat(result.hasNext()).isTrue();
	}

	@Test
	void reusesCachedGroupingWhenFingerprintSignatureIsUnchanged() {
		when(repository.fingerprintSignature(any(), any()))
				.thenReturn(Collections.singletonList(new Object[] { 2L, 2L, null }));
		when(repository.findFingerprintedPhotos(any(), any(), any()))
				.thenReturn(new PageImpl<>(List.of(photo(1L, hash(0), sample(100), LocalDateTime.now()),
						photo(2L, hash(0), sample(100), LocalDateTime.now()))));

		PhotoSimilarityService service = service(new PhotoSsimService());

		service.groups(70, PageRequest.of(0, 20));
		service.groups(70, PageRequest.of(0, 20));

		verify(repository, times(1)).findFingerprintedPhotos(any(), any(), any());
	}

	@SuppressWarnings("unchecked")
	@Test
	void recomputesWhenFingerprintSignatureChanges() {
		when(repository.fingerprintSignature(any(), any())).thenReturn(
				Collections.singletonList(new Object[] { 1L, 1L, null }),
				Collections.singletonList(new Object[] { 2L, 2L, null }));
		when(repository.findFingerprintedPhotos(any(), any(), any()))
				.thenReturn(new PageImpl<>(List.of(photo(1L, hash(0), sample(100), LocalDateTime.now()),
						photo(2L, hash(0), sample(100), LocalDateTime.now()))));

		PhotoSimilarityService service = service(new PhotoSsimService());

		service.groups(70, PageRequest.of(0, 20));
		service.groups(70, PageRequest.of(0, 20));

		verify(repository, times(2)).findFingerprintedPhotos(any(), any(), any());
	}

	@Test
	void evictFromCacheDropsAffectedGroupsWithoutRecomputing() {
		when(repository.fingerprintSignature(any(), any()))
				.thenReturn(Collections.singletonList(new Object[] { 2L, 2L, null }));
		when(repository.findFingerprintedPhotos(any(), any(), any()))
				.thenReturn(new PageImpl<>(List.of(photo(1L, hash(0), sample(100), LocalDateTime.now()),
						photo(2L, hash(0), sample(100), LocalDateTime.now()))));

		PhotoSimilarityService service = service(new PhotoSsimService());

		Assertions.assertThat(service.groups(70, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(1);

		service.evictFromCache(List.of(UuidV7.fromLegacy(2L)));

		Assertions.assertThat(service.groups(70, PageRequest.of(0, 20)).getTotalElements()).isZero();

		verify(repository, times(1)).findFingerprintedPhotos(any(), any(), any());
	}

	@Test
	void evictFromCacheDropsGroupWhenRemovedFileIsAReviewCandidate() {
		LocalDateTime older = LocalDateTime.of(2023, Month.JANUARY, 1, 10, 0);
		LocalDateTime newer = LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0);

		UUID keepId = UuidV7.fromLegacy(1L);
		UUID reviewId = UuidV7.fromLegacy(2L);

		when(repository.fingerprintSignature(any(), any()))
				.thenReturn(Collections.singletonList(new Object[] { 2L, 2L, null }));

		when(repository.findFingerprintedPhotos(any(), any(), any())).thenReturn(new PageImpl<>(
				List.of(photo(1L, hash(0), sample(100), older), photo(2L, hash(0), sample(100), newer))));

		/*
		 * Both files carry the original signals (camera subcategory, EXIF date source
		 * and a camera EXIF header). Since this is a group of similar photos with more
		 * than one original, the policy keeps only one as the highlight and sends the
		 * other to review.
		 */
		when(mediaQualityRepository.findByPublicIdIn(any())).thenReturn(List.of(
				new MediaQuality(keepId, 100, 100, older, true, MediaSubcategory.CAMERA, DateSource.EXIF, true),
				new MediaQuality(reviewId, 100, 100, newer, true, MediaSubcategory.CAMERA, DateSource.EXIF, true)));

		PhotoSimilarityService service = service(new PhotoSsimService());

		Page<SimilarPhotoGroupResponse> initial = service.groups(70, PageRequest.of(0, 20));

		Assertions.assertThat(initial.getTotalElements()).isEqualTo(1);

		SimilarPhotoGroupResponse group = initial.getContent().getFirst();

		Assertions.assertThat(group.keep().id()).isEqualTo(keepId);
		Assertions.assertThat(group.deleteCandidates()).isEmpty();
		Assertions.assertThat(group.reviewCandidates()).extracting(candidate -> candidate.id())
				.containsExactly(reviewId);

		service.evictFromCache(List.of(reviewId));

		Page<SimilarPhotoGroupResponse> cached = service.cachedPage(70, PageRequest.of(0, 20)).orElseThrow();

		Assertions.assertThat(cached.getTotalElements()).isZero();
		Assertions.assertThat(cached.getContent()).isEmpty();

		/*
		 * The removal must modify only the existing cache, without running the grouping
		 * again.
		 */
		verify(repository, times(1)).findFingerprintedPhotos(any(), any(), any());
	}

	@Test
	void cachedPageIsEmptyBeforeComputeAndPresentAfter() {
		when(repository.fingerprintSignature(any(), any()))
				.thenReturn(Collections.singletonList(new Object[] { 2L, 2L, null }));
		when(repository.findFingerprintedPhotos(any(), any(), any()))
				.thenReturn(new PageImpl<>(List.of(photo(1L, hash(0), sample(100), LocalDateTime.now()),
						photo(2L, hash(0), sample(100), LocalDateTime.now()))));

		PhotoSimilarityService service = service(new PhotoSsimService());

		Assertions.assertThat(service.isCached(70)).isFalse();
		Assertions.assertThat(service.cachedPage(70, PageRequest.of(0, 20))).isEmpty();

		verify(repository, times(0)).findFingerprintedPhotos(any(), any(), any());

		service.computeAndCache(70, (_, _) -> {
		});

		Assertions.assertThat(service.isCached(70)).isTrue();
		Assertions.assertThat(service.cachedPage(70, PageRequest.of(0, 20))).isPresent();
		Assertions.assertThat(service.cachedPage(70, PageRequest.of(0, 20)).orElseThrow().getTotalElements())
				.isEqualTo(1);

		verify(repository, times(1)).findFingerprintedPhotos(any(), any(), any());
	}

	@Test
	void computeAndCacheReportsProgressUpToTheCandidateTotal() {
		when(repository.fingerprintSignature(any(), any()))
				.thenReturn(Collections.singletonList(new Object[] { 3L, 3L, null }));
		when(repository.findFingerprintedPhotos(any(), any(), any()))
				.thenReturn(new PageImpl<>(List.of(photo(1L, hash(0), sample(100), LocalDateTime.now()),
						photo(2L, hash(0), sample(100), LocalDateTime.now()),
						photo(3L, hash(255), sample(100), LocalDateTime.now()))));

		AtomicInteger lastProcessed = new AtomicInteger();
		AtomicInteger reportedTotal = new AtomicInteger();

		service(new PhotoSsimService()).computeAndCache(70, (processed, total) -> {
			lastProcessed.set(processed);
			reportedTotal.set(total);
		});

		Assertions.assertThat(reportedTotal.get()).isEqualTo(3);
		Assertions.assertThat(lastProcessed.get()).isEqualTo(3);
	}

	private PhotoHashRawResponse photo(Long id, byte[] phash, byte[] luminance, LocalDateTime modifiedAt) {
		return new PhotoHashRawResponse(id, phash, luminance, id + ".jpg", "jpg", 100, "C:/" + id + ".jpg", "C:/",
				modifiedAt);
	}

	private PhotoHashRawResponse photoIn(Long id, String folder, byte[] phash, byte[] luminance,
			LocalDateTime modifiedAt) {
		return new PhotoHashRawResponse(id, phash, luminance, id + ".jpg", "jpg", 100, folder + "/" + id + ".jpg",
				folder, modifiedAt);
	}

	private byte[] hash(int value) {
		byte[] hash = new byte[32];

		Arrays.fill(hash, (byte) value);

		return hash;
	}

	private byte[] sample(int value) {
		byte[] sample = new byte[1024];

		Arrays.fill(sample, (byte) value);

		return sample;
	}

	private PhotoSimilarityService service(PhotoSsimService ssim) {
		NimbusFileManagerProperties properties = new NimbusFileManagerProperties("C:/workspace", List.of(), null, null, null,
				new Api(100, 2, 50), null, null, null, null);

		lenient().when(settings.intValue(any(), any(Integer.class)))
				.thenAnswer(invocation -> invocation.getArgument(1));
		lenient().when(mediaQualityRepository.findByPublicIdIn(any())).thenReturn(List.of());

		return new PhotoSimilarityService(repository,
				new DuplicateGroupAssembler(new DuplicateKeepPolicy(), mediaQualityRepository), ssim, settings,
				properties, exclusions);
	}
}