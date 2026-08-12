package br.com.jorgemelo.nimbusfilemanager.media.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
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
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.enums.GeoPresence;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection.CameraFilter;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection.MediaScaleFilter;

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

		var criteria = new MediaSearchCriteria(FileType.VIDEO, " h265 ", "  root ", " mp4 ", 2024, 5, 100L, 5000L,
				null, null, null, null, null, null);

		var pageable = PageRequest.of(1, 10, Sort.by("ignored"));

		when(mediaSearchRepository.search(any(), any())).thenReturn(new PageImpl<>(List.of(raw)));

		var page = service().search(criteria, pageable);

		Assertions.assertThat(page.getContent()).hasSize(1);
		Assertions.assertThat(page.getContent().getFirst().size().formatted()).isEqualTo("2.00 KB");
		Assertions.assertThat(page.getContent().getFirst().videoCodec()).isEqualTo("h265");

		verify(mediaSearchRepository).search(
				new MediaSearchFilter(FileType.VIDEO, "H265", "root", "mp4", 2024, 5, new MediaScaleFilter(100L,
						5000L, null, null, null), CameraFilter.ANY, null),
				PageRequest.of(1, 10));
	}

	private MediaSearchService service() {
		NimbusFileManagerProperties properties = new NimbusFileManagerProperties("C:/workspace",
				new Inventory(true, 60_000L), new Api(100, 2, 50), null, null);

		lenient().when(appSettingService.intValue(any(), any(Integer.class)))
				.thenAnswer(invocation -> invocation.getArgument(1));

		return new MediaSearchService(mediaSearchRepository, appSettingService, properties);
	}

	/** The moment the file was written to, which is what the catalog holds. */
	private Instant now() {
		return Instant.parse("2024-05-09T10:30:00Z");
	}
	/**
	 * The location filter is three-state, and the middle state is the one worth
	 * pinning down: "either way" has to reach the query as no predicate at all,
	 * not as a search for media without a place - which would silently return the
	 * opposite of what the caller meant.
	 */
	@Test
	void translatesTheLocationOptionIntoThreeStates() {
		Assertions.assertThat(boundLocation(null)).isNull();
		Assertions.assertThat(boundLocation(GeoPresence.ANY)).isNull();
		Assertions.assertThat(boundLocation(GeoPresence.WITH_LOCATION)).isTrue();
		Assertions.assertThat(boundLocation(GeoPresence.WITHOUT_LOCATION)).isFalse();
	}

	private Boolean boundLocation(GeoPresence geo) {
		when(mediaSearchRepository.search(any(), any())).thenReturn(Page.empty());

		service().search(new MediaSearchCriteria(null, null, null, null, null, null, null, null, null, null, null,
				null, null, geo), PageRequest.of(0, 10));

		ArgumentCaptor<MediaSearchFilter> filter = ArgumentCaptor.forClass(MediaSearchFilter.class);

		verify(mediaSearchRepository, atLeastOnce()).search(filter.capture(), any());

		return filter.getValue().requiresLocation();
	}

}