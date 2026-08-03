package br.com.jorgemelo.nimbusfilemanager.timeline.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineCursor;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelinePageResponse;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.enums.TimelineMediaType;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection.TimelineFilter;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection.TimelineItemProjection;
import br.com.jorgemelo.nimbusfilemanager.timeline.infrastructure.persistence.TimelineQueryRepository;

@ExtendWith(MockitoExtension.class)
class TimelineApiServiceTest {

	@Mock
	private TimelineQueryRepository repository;

	@Test
	void shouldGroupPageByDayAndCreateStableNextCursor() {
		TimelineCursorCodec codec = codec();

		TimelineService service = new TimelineService(repository, codec);

		LocalDateTime newest = LocalDateTime.of(2026, Month.JULY, 11, 12, 0);
		LocalDateTime older = LocalDateTime.of(2026, Month.JULY, 10, 10, 0);

		var one = item(3, newest);
		var two = item(2, older);
		var extra = item(1, older.minusHours(1));

		when(repository.findPage(isNull(), any(), any(), isNull(), isNull(), eq(3))).thenReturn(List.of(one, two,
				extra));

		TimelinePageResponse page = service.page(TimelineMediaType.ALL, null, TimelineFilter.NONE, 2, null, null);

		Assertions.assertThat(page.groups()).hasSize(2);
		Assertions.assertThat(page.groups().getFirst().date()).isEqualTo(newest.toLocalDate());
		Assertions.assertThat(page.groups().getLast().date()).isEqualTo(older.toLocalDate());
		Assertions.assertThat(page.hasMore()).isTrue();

		TimelineCursor cursor = codec.decode(page.nextCursor(), TimelineMediaType.ALL);

		Assertions.assertThat(cursor).isEqualTo(new TimelineCursor(older, 2, TimelineMediaType.ALL));
		Assertions.assertThat(page.groups().getFirst().items().getFirst().thumbnailUrl()).endsWith("/thumbnail?w=320");
	}

	@Test
	void shouldRejectCursorFromDifferentFilterAndInvalidLimits() {
		TimelineCursorCodec codec = codec();

		TimelineService service = new TimelineService(repository, codec);

		String cursor = codec.encode(new TimelineCursor(LocalDateTime.now(), 2, TimelineMediaType.PHOTO));

		Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> service.page(TimelineMediaType.VIDEO, null, TimelineFilter.NONE, 120, cursor, null));
		Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> service.page(TimelineMediaType.ALL, null, TimelineFilter.NONE, 251, null, null));
	}

	private TimelineCursorCodec codec() {
		ObjectMapper mapper = new ObjectMapper();

		mapper.registerModule(new JavaTimeModule());

		return new TimelineCursorCodec(mapper);
	}

	private TimelineItemProjection item(long id, LocalDateTime date) {
		return new TimelineItemProjection(id, UUID.randomUUID(), "item-" + id + ".jpg", FileType.PHOTO, date,
				DateSource.EXIF, 1920, 1080, null);
	}
}