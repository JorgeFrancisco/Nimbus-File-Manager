package br.com.jorgemelo.nimbusfilemanager.organization.application.filter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPreviewRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.projection.OrganizationCandidate;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileCategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

class OrganizationCandidateFilterTest {

	private final OrganizationCandidateFilter filter = new OrganizationCandidateFilter(
			mock(ScanExclusionService.class));

	@Test
	void shouldMatchWhenFiltersAreEmpty() {
		Assertions.assertThat(filter.matches(
				candidate("jpg", FileType.PHOTO, FileCategory.MEDIA, MediaSubcategory.CAMERA, "C:/input"),
				request(null, null, null, null, true), PathUtils.normalize("C:/input"))).isTrue();
	}

	@Test
	void shouldApplyAllFilters() {
		OrganizationCandidate candidate = candidate("JPG", FileType.PHOTO, FileCategory.MEDIA, MediaSubcategory.CAMERA,
				"C:/input");

		OrganizationPreviewRequest request = request(List.of(FileCategory.MEDIA), List.of(MediaSubcategory.CAMERA),
				List.of(".jpg"), List.of(FileType.PHOTO), false);

		Assertions.assertThat(filter.matches(candidate, request, PathUtils.normalize("C:/input"))).isTrue();
		Assertions.assertThat(filter.matches(candidate, request, PathUtils.normalize("C:/other"))).isFalse();
	}

	@Test
	void shouldRejectNonMatchingFilters() {
		OrganizationCandidate candidate = candidate("jpg", FileType.PHOTO, FileCategory.MEDIA, MediaSubcategory.CAMERA,
				"C:/input");

		Assertions.assertThat(filter.matches(candidate, request(List.of(FileCategory.DOCUMENT), null, null, null, true),
				PathUtils.normalize("C:/input"))).isFalse();
		Assertions.assertThat(filter.matches(candidate,
				request(null, List.of(MediaSubcategory.DRONE), null, null, true), PathUtils.normalize("C:/input")))
				.isFalse();
		Assertions.assertThat(filter.matches(candidate, request(null, null, List.of("mp4"), null, true),
				PathUtils.normalize("C:/input"))).isFalse();
		Assertions.assertThat(filter.matches(candidate, request(null, null, null, List.of(FileType.VIDEO), true),
				PathUtils.normalize("C:/input"))).isFalse();
	}

	@Test
	void shouldRejectExcludedCandidates() {
		ScanExclusionService scanExclusionService = mock(ScanExclusionService.class);

		OrganizationCandidateFilter filtered = new OrganizationCandidateFilter(scanExclusionService);

		OrganizationCandidate candidate = candidate("jpg", FileType.PHOTO, FileCategory.MEDIA, MediaSubcategory.CAMERA,
				"C:/input/.git");

		when(scanExclusionService.isExcluded(any())).thenReturn(true);

		Assertions.assertThat(
				filtered.matches(candidate, request(null, null, null, null, true), PathUtils.normalize("C:/input")))
				.isFalse();
	}

	private OrganizationPreviewRequest request(List<FileCategory> categories, List<MediaSubcategory> subcategories,
			List<String> extensions, List<FileType> fileTypes, Boolean recursive) {
		return new OrganizationPreviewRequest("C:/input", "C:/target", recursive, null, null, null, null, null,
				categories, subcategories, extensions, fileTypes);
	}

	private OrganizationCandidate candidate(String extension, FileType fileType, FileCategory category,
			MediaSubcategory subcategory, String currentFolder) {
		return new OrganizationCandidate(1L, "photo." + extension, extension, fileType, 100L,
				currentFolder + "/photo." + extension, currentFolder, 2024, 5, 9, "202405",
				LocalDateTime.of(2024, Month.MAY, 9, 10, 30), category, subcategory);
	}
}