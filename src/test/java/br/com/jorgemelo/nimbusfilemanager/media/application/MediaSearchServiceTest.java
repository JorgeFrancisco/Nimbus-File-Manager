package br.com.jorgemelo.nimbusfilemanager.media.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import br.com.jorgemelo.nimbusfilemanager.media.application.dto.MediaSearchCriteria;
import br.com.jorgemelo.nimbusfilemanager.media.domain.repository.MediaSearchRepository;
import br.com.jorgemelo.nimbusfilemanager.media.domain.repository.projection.MediaSearchFilter;
import br.com.jorgemelo.nimbusfilemanager.media.domain.repository.projection.MediaSearchRawResponse;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Api;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Inventory;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;

@ExtendWith(MockitoExtension.class)
class MediaSearchServiceTest {

	@Mock
	private MediaSearchRepository mediaSearchRepository;

	@Mock
	private AppSettingService appSettingService;

	@Test
	void searchShouldNormalizeCriteriaAndMapSize() {
		var raw = new MediaSearchRawResponse(1L, "video.mp4", "mp4", "VIDEO", 2048, "C:/video.mp4", "C:/", now(), now(),
				2024, 5, 9, "202405", "h265", "aac", 10.5, 1920, 1080, "Canon", "R5");

		var criteria = new MediaSearchCriteria(FileType.VIDEO, " h265 ", "  root ", " mp4 ", 2024, 5, 100L, 5000L);

		var pageable = PageRequest.of(1, 10, Sort.by("ignored"));

		when(mediaSearchRepository.search(any(), any())).thenReturn(new PageImpl<>(List.of(raw)));

		var page = service().search(criteria, pageable);

		Assertions.assertThat(page.getContent()).hasSize(1);
		Assertions.assertThat(page.getContent().getFirst().size().formatted()).isEqualTo("2.00 KB");
		Assertions.assertThat(page.getContent().getFirst().videoCodec()).isEqualTo("h265");

		verify(mediaSearchRepository).search(
				new MediaSearchFilter(FileType.VIDEO, "H265", "root", "mp4", 2024, 5, 100L, 5000L),
				PageRequest.of(1, 10));
	}

	private MediaSearchService service() {
		NimbusFileManagerProperties properties = new NimbusFileManagerProperties("C:/workspace", null,
				new Inventory(true, 60_000L), new Api(100, 2, 50), null, null);

		lenient().when(appSettingService.intValue(any(), any(Integer.class)))
				.thenAnswer(invocation -> invocation.getArgument(1));

		return new MediaSearchService(mediaSearchRepository, appSettingService, properties);
	}

	private LocalDateTime now() {
		return LocalDateTime.of(2024, Month.MAY, 9, 10, 30);
	}
}