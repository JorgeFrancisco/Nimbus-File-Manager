package br.com.jorgemelo.nimbusfilemanager.timeline.infrastructure.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.Month;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.TimelineService;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineIndex;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelinePageResponse;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineUndatedPageResponse;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.enums.TimelineMediaType;

class TimelineControllerTest {

	private final TimelineService timelineService = mock(TimelineService.class);
	private final TimelineController controller = new TimelineController(timelineService);

	/**
	 * The index endpoint is the only one that unwraps the media type: the service
	 * takes a {@link FileType}, and {@code ALL} must reach it as {@code null}.
	 */
	@Test
	void indexShouldUnwrapTheMediaTypeIntoAFileType() {
		List<MediaSubcategory> subcategories = List.of(MediaSubcategory.CAMERA);
		TimelineIndex index = new TimelineIndex(10, 8, 2, List.of());

		when(timelineService.index(FileType.PHOTO, subcategories)).thenReturn(index);

		Assertions.assertThat(controller.index(TimelineMediaType.PHOTO, subcategories)).isSameAs(index);
	}

	@Test
	void indexShouldPassANullFileTypeForAll() {
		controller.index(TimelineMediaType.ALL, null);

		verify(timelineService).index(null, null);
	}

	/**
	 * The paginated endpoints keep the media type as-is - the service resolves it
	 * together with the keyset cursor.
	 */
	@Test
	void itemsShouldForwardTheKeysetArgumentsUnchanged() {
		LocalDate from = LocalDate.of(2026, Month.JULY, 25);
		List<MediaSubcategory> subcategories = List.of(MediaSubcategory.SCREENSHOT);
		TimelinePageResponse page = new TimelinePageResponse(List.of(), "cursor-2", true);

		when(timelineService.page(TimelineMediaType.VIDEO, subcategories, 50, "cursor-1", from)).thenReturn(page);

		Assertions.assertThat(controller.items(TimelineMediaType.VIDEO, subcategories, 50, "cursor-1", from))
				.isSameAs(page);
	}

	@Test
	void undatedShouldForwardTheKeysetArgumentsUnchanged() {
		TimelineUndatedPageResponse page = new TimelineUndatedPageResponse(List.of(), null, false);

		when(timelineService.undated(TimelineMediaType.ALL, null, 120, null)).thenReturn(page);

		Assertions.assertThat(controller.undated(TimelineMediaType.ALL, null, 120, null)).isSameAs(page);
	}
}