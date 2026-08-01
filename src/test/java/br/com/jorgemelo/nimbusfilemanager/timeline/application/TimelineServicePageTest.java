package br.com.jorgemelo.nimbusfilemanager.timeline.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineCursor;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineDayResponse;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelinePageResponse;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineUndatedCursor;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineUndatedPageResponse;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.enums.TimelineMediaType;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection.TimelineItemProjection;
import br.com.jorgemelo.nimbusfilemanager.timeline.infrastructure.persistence.TimelineQueryRepository;

/**
 * Cursor-based paging paths of the timeline service not covered by
 * {@link TimelineServiceTest}: limit validation, cursor/from conflict, day
 * grouping with next-cursor emission and the undated feed.
 */
class TimelineServicePageTest {

	private final TimelineQueryRepository repository = mock(TimelineQueryRepository.class);
	private final TimelineCursorCodec cursorCodec = mock(TimelineCursorCodec.class);
	private final TimelineService service = new TimelineService(repository, cursorCodec);

	private TimelineItemProjection item(long id, LocalDateTime captureDate) {
		return new TimelineItemProjection(id, UUID.randomUUID(), "file-" + id + ".jpg", FileType.PHOTO, captureDate,
				null, 1920, 1080, null);
	}

	@Test
	void pageRejectsLimitOutsideRange() {
		Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> service.page(TimelineMediaType.ALL, null, 0, null, null))
				.withMessageContaining("between 1 and 250");
		Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> service.page(TimelineMediaType.ALL, null, 251, null, null))
				.withMessageContaining("between 1 and 250");
	}

	@Test
	void pageRejectsCursorAndFromCombined() {
		Assertions.assertThatIllegalArgumentException().isThrownBy(
				() -> service.page(TimelineMediaType.ALL, null, 10, "cursor", LocalDate.of(2026, Month.JULY, 1)))
				.withMessageContaining("cannot be combined");
	}

	@Test
	void pageWithCursorGroupsByDayAndEmitsNextCursor() {
		LocalDateTime d1 = LocalDateTime.parse("2026-07-12T10:00:00");
		LocalDateTime d2 = LocalDateTime.parse("2026-07-11T09:00:00");

		TimelineCursor decoded = new TimelineCursor(LocalDateTime.parse("2026-07-13T00:00:00"), 99L,
				TimelineMediaType.ALL);

		when(cursorCodec.decode("cur", TimelineMediaType.ALL)).thenReturn(decoded);
		// limit 2 -> asks for 3; returning 3 rows means hasMore = true.
		when(repository.findPage(isNull(), any(), eq(decoded.captureDate()), eq(99L), eq(3)))
				.thenReturn(List.of(item(1, d1), item(2, d2), item(3, d2)));
		when(cursorCodec.encode(any(TimelineCursor.class))).thenReturn("next-cursor");

		TimelinePageResponse response = service.page(TimelineMediaType.ALL, null, 2, "cur", null);

		Assertions.assertThat(response.hasMore()).isTrue();
		Assertions.assertThat(response.nextCursor()).isEqualTo("next-cursor");
		Assertions.assertThat(response.groups()).extracting(TimelineDayResponse::date)
				.containsExactly(LocalDate.of(2026, Month.JULY, 12), LocalDate.of(2026, Month.JULY, 11));
	}

	@Test
	void pageWithoutCursorReturnsAllWhenNoMorePages() {
		when(repository.findPage(isNull(), any(), isNull(), isNull(), eq(11)))
				.thenReturn(List.of(item(1, LocalDateTime.now())));

		TimelinePageResponse response = service.page(TimelineMediaType.ALL, null, 10, null, null);

		Assertions.assertThat(response.hasMore()).isFalse();
		Assertions.assertThat(response.nextCursor()).isNull();
		Assertions.assertThat(response.groups()).hasSize(1);
	}

	@Test
	void undatedEmitsNextCursorWhenMorePagesExist() {
		when(repository.findUndatedPage(eq(FileType.PHOTO), any(), isNull(), eq(3)))
				.thenReturn(List.of(item(1, null), item(2, null), item(3, null)));
		when(cursorCodec.encodeUndated(any(TimelineUndatedCursor.class))).thenReturn("u-next");

		TimelineUndatedPageResponse response = service.undated(TimelineMediaType.PHOTO, null, 2, null);

		Assertions.assertThat(response.hasMore()).isTrue();
		Assertions.assertThat(response.nextCursor()).isEqualTo("u-next");
		Assertions.assertThat(response.items()).hasSize(2);
	}

	@Test
	void undatedDecodesCursorAndReturnsWithoutNextWhenNoMore() {
		when(cursorCodec.decodeUndated("uc", TimelineMediaType.PHOTO))
				.thenReturn(new TimelineUndatedCursor(7L, TimelineMediaType.PHOTO));
		when(repository.findUndatedPage(eq(FileType.PHOTO), any(), eq(7L), eq(11))).thenReturn(List.of(item(1, null)));

		TimelineUndatedPageResponse response = service.undated(TimelineMediaType.PHOTO, null, 10, "uc");

		Assertions.assertThat(response.hasMore()).isFalse();
		Assertions.assertThat(response.nextCursor()).isNull();
		Assertions.assertThat(response.items()).hasSize(1);
	}

	@Test
	void undatedRejectsInvalidLimit() {
		Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> service.undated(TimelineMediaType.PHOTO, null, 0, null))
				.withMessageContaining("between 1 and 250");
	}
}