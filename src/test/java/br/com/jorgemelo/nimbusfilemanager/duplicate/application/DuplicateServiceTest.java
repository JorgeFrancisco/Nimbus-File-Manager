package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.DuplicateRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.MediaQualityRepository;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateFileRawResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateFileWithShaRawResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateGroupRawResponse;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateSummaryProjection;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.MediaQuality;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Api;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Inventory;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.util.UuidV7;

@ExtendWith(MockitoExtension.class)
class DuplicateServiceTest {

	@Mock
	private DuplicateRepository duplicateRepository;

	@Mock
	private DuplicateSummaryProjection summaryProjection;

	@Mock
	private AppSettingService appSettingService;

	@Mock
	private MediaQualityRepository mediaQualityRepository;

	@Test
	void groupsShouldMapRawResponses() {
		when(duplicateRepository.findDuplicateGroups(any(), eq(PageRequest.of(0, 10))))
				.thenReturn(new PageImpl<>(List.of(new DuplicateGroupRawResponse("hash", 3, 300, 200))));

		var page = service().groups(PageRequest.of(0, 10, Sort.by("ignored")), null);

		Assertions.assertThat(page.getContent()).hasSize(1);
		Assertions.assertThat(page.getContent().getFirst().sha256()).isEqualTo("hash");
		Assertions.assertThat(page.getContent().getFirst().wastedSize().bytes()).isEqualTo(200);
	}

	@Test
	void groupsShouldClampPageSizeToConfiguredMax() {
		when(duplicateRepository.findDuplicateGroups(any(), eq(PageRequest.of(0, 100))))
				.thenReturn(new PageImpl<>(List.of()));

		service().groups(PageRequest.of(0, 500), null);

		verify(duplicateRepository).findDuplicateGroups(any(), eq(PageRequest.of(0, 100)));
	}

	@Test
	void filesShouldMapRawResponses() {
		LocalDateTime modifiedAt = LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0);
		when(duplicateRepository.findDuplicateFiles("hash")).thenReturn(
				List.of(new DuplicateFileRawResponse(1L, "a.jpg", "jpg", "PHOTO", 100, "C:/a.jpg", "C:/", modifiedAt)));

		var files = service().files("hash");

		Assertions.assertThat(files).hasSize(1);
		Assertions.assertThat(files.getFirst().size().bytes()).isEqualTo(100);
		Assertions.assertThat(files.getFirst().modifiedAt()).isEqualTo(modifiedAt);
	}

	@Test
	void summaryShouldMapProjection() {
		when(summaryProjection.getGroups()).thenReturn(2L);
		when(summaryProjection.getDuplicatedFiles()).thenReturn(5L);
		when(summaryProjection.getTotalSizeBytes()).thenReturn(500L);
		when(summaryProjection.getWastedSizeBytes()).thenReturn(300L);
		when(duplicateRepository.summary()).thenReturn(summaryProjection);

		var summary = service().summary();

		Assertions.assertThat(summary.groups()).isEqualTo(2);
		Assertions.assertThat(summary.wastedSize().bytes()).isEqualTo(300);
	}

	@Test
	void candidatesShouldSelectOldestFileToKeep() {
		LocalDateTime older = LocalDateTime.of(2023, Month.JANUARY, 1, 10, 0);
		LocalDateTime newer = LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0);

		when(duplicateRepository.findDuplicateGroups(any(), eq(PageRequest.of(0, 10))))
				.thenReturn(new PageImpl<>(List.of(new DuplicateGroupRawResponse("hash", 2, 200, 100))));
		when(duplicateRepository.findDuplicateFilesForShas(eq(List.of("hash")), any())).thenReturn(List.of(
				new DuplicateFileWithShaRawResponse("hash", 2L, "new.jpg", "jpg", "PHOTO", 100, "C:/new.jpg", "C:/",
						newer),
				new DuplicateFileWithShaRawResponse("hash", 1L, "old.jpg", "jpg", "PHOTO", 100, "C:/old.jpg", "C:/",
						older)));

		UUID id1 = UuidV7.fromLegacy(1L);
		UUID id2 = UuidV7.fromLegacy(2L);

		when(mediaQualityRepository.findByPublicIdIn(any())).thenReturn(
				List.of(new MediaQuality(id1, 100, 100, older, true, MediaSubcategory.CAMERA, DateSource.EXIF, true),
						new MediaQuality(id2, 100, 100, newer, true, MediaSubcategory.CAMERA, DateSource.EXIF, true)));

		var candidates = service().candidates(PageRequest.of(0, 10), null);

		Assertions.assertThat(candidates.getContent().getFirst().keep().id()).isEqualTo(UuidV7.fromLegacy(1L));
		Assertions.assertThat(candidates.getContent().getFirst().deleteCandidates()).extracting("id")
				.containsExactly(UuidV7.fromLegacy(2L));
	}

	@Test
	void candidatesShouldSelectLowestIdWhenModifiedAtTies() {
		LocalDateTime modifiedAt = LocalDateTime.of(2024, Month.JANUARY, 1, 10, 0);

		when(duplicateRepository.findDuplicateGroups(any(), eq(PageRequest.of(0, 10))))
				.thenReturn(new PageImpl<>(List.of(new DuplicateGroupRawResponse("hash", 3, 300, 200))));
		when(duplicateRepository.findDuplicateFilesForShas(eq(List.of("hash")), any())).thenReturn(List.of(
				new DuplicateFileWithShaRawResponse("hash", 3L, "c.jpg", "jpg", "PHOTO", 100, "C:/c.jpg", "C:/",
						modifiedAt),
				new DuplicateFileWithShaRawResponse("hash", 1L, "a.jpg", "jpg", "PHOTO", 100, "C:/a.jpg", "C:/",
						modifiedAt),
				new DuplicateFileWithShaRawResponse("hash", 2L, "b.jpg", "jpg", "PHOTO", 100, "C:/b.jpg", "C:/",
						modifiedAt)));

		UUID id1 = UuidV7.fromLegacy(1L);
		UUID id2 = UuidV7.fromLegacy(2L);
		UUID id3 = UuidV7.fromLegacy(3L);

		when(mediaQualityRepository.findByPublicIdIn(any())).thenReturn(List.of(
				new MediaQuality(id1, 100, 100, modifiedAt, true, MediaSubcategory.CAMERA, DateSource.EXIF, true),
				new MediaQuality(id2, 100, 100, modifiedAt, true, MediaSubcategory.CAMERA, DateSource.EXIF, true),
				new MediaQuality(id3, 100, 100, modifiedAt, true, MediaSubcategory.CAMERA, DateSource.EXIF, true)));

		var candidates = service().candidates(PageRequest.of(0, 10), null);

		Assertions.assertThat(candidates.getContent().getFirst().keep().id()).isEqualTo(UuidV7.fromLegacy(1L));
		Assertions.assertThat(candidates.getContent().getFirst().deleteCandidates()).extracting("id")
				.containsExactly(UuidV7.fromLegacy(3L), UuidV7.fromLegacy(2L));
	}

	@Test
	void candidatesShouldNotQueryFilesWhenPageIsEmpty() {
		when(duplicateRepository.findDuplicateGroups(any(), eq(PageRequest.of(0, 10))))
				.thenReturn(new PageImpl<>(List.of()));

		service().candidates(PageRequest.of(0, 10), null);

		verify(duplicateRepository, never()).findDuplicateFilesForShas(any(), any());
	}

	private DuplicateService service() {
		NimbusFileManagerProperties properties = new NimbusFileManagerProperties("C:/workspace", List.of(), null,
				new Inventory(true, 60_000L), new Api(100, 2, 50), null, null, null);

		// Mimics an unconfigured AppSettingService (no admin override stored), same as
		// the real
		// intValue(key, fallback) behavior when nothing overrides the property defaults
		// above.
		lenient().when(appSettingService.intValue(any(), any(Integer.class)))
				.thenAnswer(invocation -> invocation.getArgument(1));

		return new DuplicateService(duplicateRepository, appSettingService, properties,
				new DuplicateGroupAssembler(new DuplicateKeepPolicy(), mediaQualityRepository));
	}
}