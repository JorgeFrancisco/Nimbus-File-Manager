package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.AnalyzedGroup;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.FingerprintAlgorithm;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.constants.SimilarityConstants;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityComposition;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.SimilarityFamily;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaFingerprintRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.CompositionRow;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaQualityRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.MediaQuality;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.PhotoHashRawResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

class PhotoSimilarityServiceTest {
	private final MediaFingerprintRepository repository = mock(MediaFingerprintRepository.class);
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

		List<AnalyzedGroup> result = service(new PhotoSsimService()).analyze(70, (_, _) -> {
		}).groups();

		Assertions.assertThat(result).hasSize(1);

		AnalyzedGroup group = result.getFirst();

		Assertions.assertThat(group.members()).hasSize(2);
		Assertions.assertThat(group.similarityPercent()).isEqualTo(100);
		Assertions.assertThat(group.members().getFirst().mediaPublicId()).isEqualTo(UuidV7.fromLegacy(1L));
	}

	@Test
	void excludedFileIsDroppedBeforeGroupingSoItsGroupNeverForms() {
		LocalDateTime when = LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0);

		when(repository.findFingerprintedPhotos(any(), any(), any())).thenReturn(
				new PageImpl<>(List.of(photo(1L, hash(0), sample(100), when), photo(2L, hash(0), sample(100), when))));
		when(exclusions.excludedFilePublicIds()).thenReturn(List.of(UuidV7.fromLegacy(2L)));

		List<AnalyzedGroup> result = service(new PhotoSsimService()).analyze(70, (_, _) -> {
		}).groups();

		// Only photo 1 survives the exclusion filter, so the identical pair can never
		// group.
		Assertions.assertThat(result).isEmpty();
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

		List<AnalyzedGroup> result = service(new PhotoSsimService()).analyze(70, (_, _) -> {
		}).groups();

		Assertions.assertThat(result).hasSize(1);
		Assertions.assertThat(result.getFirst().members()).hasSize(2);
	}

	@Test
	void identicalPHashDoesNotOverrideLowSsim() {
		when(repository.findFingerprintedPhotos(any(), any(), any()))
				.thenReturn(new PageImpl<>(List.of(photo(1L, hash(0), sample(0), LocalDateTime.now()),
						photo(2L, hash(0), sample(255), LocalDateTime.now()))));

		Assertions.assertThat(service(new PhotoSsimService()).analyze(70, (_, _) -> {
		}).groups()).isEmpty();
	}

	@Test
	void requestBelowFloorIsClampedUsingSsim() {
		PhotoSsimService ssim = mock(PhotoSsimService.class);

		when(ssim.similarityPercent(any(), any())).thenReturn(60);
		when(repository.findFingerprintedPhotos(any(), any(), any()))
				.thenReturn(new PageImpl<>(List.of(photo(1L, hash(0), sample(1), LocalDateTime.now()),
						photo(2L, hash(0), sample(2), LocalDateTime.now()))));

		Assertions.assertThat(service(ssim).analyze(50, (_, _) -> {
		}).groups()).isEmpty();
	}

	@Test
	void requestAboveCeilingIsClampedToExactSsimOnly() {
		when(repository.findFingerprintedPhotos(any(), any(), any()))
				.thenReturn(new PageImpl<>(List.of(photo(1L, hash(0), sample(100), LocalDateTime.now()),
						photo(2L, hash(0), sample(100), LocalDateTime.now()))));

		Assertions.assertThat(service(new PhotoSsimService()).analyze(150, (_, _) -> {
		}).groups()).hasSize(1);
	}

	@Test
	void pHashBeyondCandidateRadiusIsRejectedBeforeSsim() {
		when(repository.findFingerprintedPhotos(any(), any(), any()))
				.thenReturn(new PageImpl<>(List.of(photo(1L, hash(0), sample(100), LocalDateTime.now()),
						photo(2L, hash(255), sample(100), LocalDateTime.now()))));

		Assertions.assertThat(service(new PhotoSsimService()).analyze(70, (_, _) -> {
		}).groups()).isEmpty();
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

		List<AnalyzedGroup> result = service(ssim).analyze(90, (_, _) -> {
		}).groups();

		Assertions.assertThat(result).hasSize(1);
		Assertions.assertThat(result.getFirst().members()).hasSize(2);
	}

	@Test
	void distantClustersBecomeSeparateGroups() {
		when(repository.findFingerprintedPhotos(any(), any(), any()))
				.thenReturn(new PageImpl<>(List.of(photo(1L, hash(0), sample(10), LocalDateTime.now()),
						photo(2L, hash(0), sample(10), LocalDateTime.now()),
						photo(3L, hash(255), sample(20), LocalDateTime.now()),
						photo(4L, hash(255), sample(20), LocalDateTime.now()))));

		List<AnalyzedGroup> result = service(new PhotoSsimService()).analyze(70, (_, _) -> {
		}).groups();

		Assertions.assertThat(result).hasSize(2);

		Assertions.assertThat(result.getFirst().members()).hasSize(2);
		Assertions.assertThat(result.get(1).members()).hasSize(2);
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

	@Test
	void theFamilyNamesTheAlgorithmAndEveryEffectiveParameter() {
		when(exclusions.signature()).thenReturn("none");

		PhotoSimilarityService service = service(new PhotoSsimService());

		SimilarityFamily family = service.family(70);

		Assertions.assertThat(service.mediaType()).isEqualTo(FileType.PHOTO);
		Assertions.assertThat(family.mediaType()).isEqualTo(FileType.PHOTO);
		Assertions.assertThat(family.algorithmId()).isEqualTo(FingerprintAlgorithm.FFMPEG_LANCZOS_PHASH_256_V1);
		Assertions.assertThat(family.groupingVersion()).isEqualTo(SimilarityConstants.GROUPING_VERSION);
		Assertions.assertThat(family.parametersDigest()).hasSize(64);
	}

	@Test
	void aStricterThresholdIsADifferentFamilyAndSoIsADifferentSetOfExclusions() {
		when(exclusions.signature()).thenReturn("none");

		PhotoSimilarityService service = service(new PhotoSsimService());

		String atSeventy = service.family(70).parametersDigest();

		Assertions.assertThat(service.family(95).parametersDigest()).isNotEqualTo(atSeventy);

		when(exclusions.signature()).thenReturn("one-folder");

		Assertions.assertThat(service.family(70).parametersDigest()).isNotEqualTo(atSeventy);
	}

	@Test
	void theCompositionCountsWhatIsEligibleAndNamesWhatWillBeAnalysed() {
		UUID first = UuidV7.fromLegacy(1L);
		UUID second = UuidV7.fromLegacy(2L);

		when(repository.countEligibleForSimilarity(any(), any())).thenReturn(9);
		when(repository.findPhotoCompositionRows(any(), any(), any()))
				.thenReturn(List.of(new CompositionRow(first, "C:/Fotos"), new CompositionRow(second, "C:/Outros")));

		SimilarityComposition composition = service(new PhotoSsimService()).composition();

		Assertions.assertThat(composition.eligibleCount()).isEqualTo(9);
		Assertions.assertThat(composition.analyzedCount()).isEqualTo(2);
		Assertions.assertThat(composition.candidateLimit()).isEqualTo(service(new PhotoSsimService()).candidateLimit());
		Assertions.assertThat(composition.selectionPolicy()).isEqualTo(SimilarityConstants.SELECTION_OLDEST_FIRST);
		Assertions.assertThat(composition.digest()).hasSize(64);
		Assertions.assertThat(composition.coverageComplete()).isFalse();
	}

	@Test
	void aLibraryWithinTheCapReportsCompleteCoverage() {
		when(repository.countEligibleForSimilarity(any(), any())).thenReturn(1);
		when(repository.findPhotoCompositionRows(any(), any(), any()))
				.thenReturn(List.of(new CompositionRow(UuidV7.fromLegacy(1L), "C:/Fotos")));

		Assertions.assertThat(service(new PhotoSsimService()).composition().coverageComplete()).isTrue();
	}

	private PhotoSimilarityService service(PhotoSsimService ssim) {
		lenient().when(mediaQualityRepository.findByPublicIdIn(any())).thenReturn(List.of());

		return new PhotoSimilarityService(repository,
				new DuplicateGroupAssembler(new DuplicateKeepPolicy(), mediaQualityRepository), ssim, exclusions);
	}
}