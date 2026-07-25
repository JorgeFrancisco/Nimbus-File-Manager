package br.com.jorgemelo.nimbusfilemanager.media.infrastructure.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import br.com.jorgemelo.nimbusfilemanager.media.application.MediaSearchService;
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.MediaSearchCriteria;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;

/**
 * Proves the {@code @ModelAttribute MediaSearchCriteria} binding: a real HTTP
 * GET must map every query param into the record (enum, Integer and Long
 * components included), which a direct method call could never exercise.
 */
class MediaControllerTest {

	@Test
	void searchBindsEveryQueryParamIntoTheCriteriaRecord() throws Exception {
		MediaSearchService mediaSearchService = mock(MediaSearchService.class);
		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new MediaController(mediaSearchService))
				.setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();

		when(mediaSearchService.search(any(), any())).thenReturn(new PageImpl<>(List.of()));

		// The endpoint returns a PagedResponse (a plain record), so it serializes
		// cleanly in standalone MockMvc and yields the stable envelope clients rely on
		// - unlike a raw Page, whose JSON is unsupported here and unstable in general.
		mockMvc.perform(get("/api/media").param("fileType", "PHOTO").param("codec", "h264").param("folder", "D:/x")
				.param("extension", "jpg").param("year", "2026").param("month", "1").param("minSizeBytes", "10")
				.param("maxSizeBytes", "99").param("size", "50")).andExpect(status().isOk())
				.andExpect(jsonPath("$.content").isArray()).andExpect(jsonPath("$.last").value(true))
				.andExpect(jsonPath("$.totalElements").value(0));

		ArgumentCaptor<MediaSearchCriteria> criteria = ArgumentCaptor.forClass(MediaSearchCriteria.class);
		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		verify(mediaSearchService).search(criteria.capture(), pageable.capture());

		Assertions.assertThat(criteria.getValue())
				.isEqualTo(new MediaSearchCriteria(FileType.PHOTO, "h264", "D:/x", "jpg", 2026, 1, 10L, 99L));
		Assertions.assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
	}
}