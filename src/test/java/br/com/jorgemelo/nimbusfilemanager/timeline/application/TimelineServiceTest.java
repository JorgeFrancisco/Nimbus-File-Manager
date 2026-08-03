package br.com.jorgemelo.nimbusfilemanager.timeline.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.Month;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineCountSummary;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineIndex;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineMonthCount;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineYearCount;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.enums.TimelineMediaType;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection.TimelineFilter;
import br.com.jorgemelo.nimbusfilemanager.timeline.infrastructure.persistence.TimelineQueryRepository;

@ExtendWith(MockitoExtension.class)
class TimelineServiceTest {

	@Mock
	private TimelineQueryRepository repository;
	@Mock
	private TimelineCursorCodec cursorCodec;

	@Test
	void shouldBuildDescendingYearAndMonthIndexWithTotals() {
		when(repository.findCountSummary(eq(FileType.PHOTO), any(), any())).thenReturn(new TimelineCountSummary(36,
				35, 1));
		when(repository.findMonthCounts(eq(FileType.PHOTO), any(), any()))
				.thenReturn(List.of(new TimelineMonthCount(2026, 7, 10), new TimelineMonthCount(2026, 6, 20),
						new TimelineMonthCount(2025, 12, 5)));

		TimelineIndex index = new TimelineService(repository, cursorCodec).index(FileType.PHOTO, null,
				TimelineFilter.NONE);

		Assertions.assertThat(index.totalItems()).isEqualTo(36);
		Assertions.assertThat(index.datedItems()).isEqualTo(35);
		Assertions.assertThat(index.undatedItems()).isEqualTo(1);
		Assertions.assertThat(index.years()).containsExactly(
				new TimelineYearCount(2026, 30,
						List.of(new TimelineMonthCount(2026, 7, 10), new TimelineMonthCount(2026, 6, 20))),
				new TimelineYearCount(2025, 5, List.of(new TimelineMonthCount(2025, 12, 5))));

		verify(repository).findCountSummary(eq(FileType.PHOTO), any(), any());
		verify(repository).findMonthCounts(eq(FileType.PHOTO), any(), any());
	}

	@Test
	void shouldSupportAllVisualMediaAndEmptyLibrary() {
		when(repository.findCountSummary(isNull(), any(), any())).thenReturn(new TimelineCountSummary(0, 0, 0));
		when(repository.findMonthCounts(isNull(), any(), any())).thenReturn(List.of());

		TimelineIndex index = new TimelineService(repository, cursorCodec).index(null, null, TimelineFilter.NONE);

		Assertions.assertThat(index).isEqualTo(new TimelineIndex(0, 0, 0, List.of()));
	}

	@Test
	void shouldForwardTheSelectedSubcategoriesAsEnumNames() {
		when(repository.findCountSummary(FileType.PHOTO, List.of("CAMERA", "WHATSAPP"), TimelineFilter.NONE))
				.thenReturn(new TimelineCountSummary(2, 2, 0));
		when(repository.findMonthCounts(FileType.PHOTO, List.of("CAMERA", "WHATSAPP"),
				TimelineFilter.NONE)).thenReturn(List.of());

		new TimelineService(repository, cursorCodec).index(FileType.PHOTO,
				List.of(MediaSubcategory.CAMERA, MediaSubcategory.WHATSAPP), TimelineFilter.NONE);

		verify(repository).findCountSummary(FileType.PHOTO, List.of("CAMERA", "WHATSAPP"), TimelineFilter.NONE);
		verify(repository).findMonthCounts(FileType.PHOTO, List.of("CAMERA", "WHATSAPP"), TimelineFilter.NONE);
	}

	@Test
	void shouldRejectNonVisualType() {
		TimelineService service = new TimelineService(repository, cursorCodec);

		Assertions.assertThatIllegalArgumentException().isThrownBy(() -> service.index(FileType.AUDIO, null,
				TimelineFilter.NONE))
				.withMessageContaining("photo or video");
	}

	@Test
	void monthNavigationShouldExcludeMidnightOfTheFollowingDay() {
		TimelineService service = new TimelineService(repository, cursorCodec);

		LocalDate lastDayOfJuly = LocalDate.of(2022, Month.JULY, 31);

		when(repository.findPage(isNull(), any(), any(), eq(lastDayOfJuly.plusDays(1).atStartOfDay()),
				eq(Long.MIN_VALUE), eq(121))).thenReturn(List.of());

		service.page(TimelineMediaType.ALL, null, TimelineFilter.NONE, 120, null, lastDayOfJuly);

		verify(repository).findPage(isNull(), any(), any(), eq(LocalDate.of(2022, Month.AUGUST, 1).atStartOfDay()),
				eq(Long.MIN_VALUE), eq(121));
	}
}