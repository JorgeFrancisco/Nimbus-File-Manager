package br.com.jorgemelo.nimbusfilemanager.timeline.application;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineCountSummary;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineCursor;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineDayResponse;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineIndex;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineItemResponse;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineMonthCount;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelinePageResponse;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineUndatedCursor;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineUndatedPageResponse;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineYearCount;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.enums.TimelineMediaType;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection.TimelineFilter;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection.TimelineItemProjection;
import br.com.jorgemelo.nimbusfilemanager.timeline.infrastructure.persistence.TimelineQueryRepository;

@Service
public class TimelineService {

	private final TimelineQueryRepository timelineQueryRepository;
	private final TimelineCursorCodec cursorCodec;

	public TimelineService(TimelineQueryRepository timelineQueryRepository, TimelineCursorCodec cursorCodec) {
		this.timelineQueryRepository = timelineQueryRepository;
		this.cursorCodec = cursorCodec;
	}

	@Transactional(readOnly = true)
	public TimelinePageResponse page(TimelineMediaType type, Collection<MediaSubcategory> subcategories,
			TimelineFilter filter, int limit, String encodedCursor, LocalDate from) {
		validateLimit(limit);

		if (encodedCursor != null && from != null) {
			throw new IllegalArgumentException("Timeline cursor and from cannot be combined");
		}

		TimelineCursor cursor = encodedCursor == null ? null : cursorCodec.decode(encodedCursor, type);

		LocalDateTime cursorDate;

		if (cursor != null) {
			cursorDate = cursor.captureDate();
		} else if (from == null) {
			cursorDate = null;
		} else {
			cursorDate = from.plusDays(1).atStartOfDay();
		}

		Long cursorId = null;

		if (cursor != null) {
			cursorId = cursor.internalId();
		} else if (from != null) {
			// The query uses a strict tuple comparison. MIN_VALUE excludes media whose
			// normalized capture time is exactly midnight on the following day; MAX_VALUE
			// incorrectly included those records and made July start at August 1st.
			cursorId = Long.MIN_VALUE;
		}

		List<TimelineItemProjection> rows = timelineQueryRepository.findPage(type.fileType(),
				resolveSubcategories(subcategories), filter, cursorDate, cursorId, limit + 1);

		boolean hasMore = rows.size() > limit;

		List<TimelineItemProjection> visible = hasMore ? rows.subList(0, limit) : rows;

		Map<LocalDate, List<TimelineItemResponse>> byDay = new LinkedHashMap<>();

		visible.forEach(item -> byDay.computeIfAbsent(item.captureDate().toLocalDate(), _ -> new ArrayList<>())
				.add(toResponse(item)));

		List<TimelineDayResponse> groups = byDay.entrySet().stream()
				.map(entry -> new TimelineDayResponse(entry.getKey(), entry.getValue())).toList();

		String nextCursor = null;

		if (hasMore && !visible.isEmpty()) {
			TimelineItemProjection last = visible.getLast();

			nextCursor = cursorCodec.encode(new TimelineCursor(last.captureDate(), last.internalId(), type));
		}

		return new TimelinePageResponse(groups, nextCursor, hasMore);
	}

	@Transactional(readOnly = true)
	public TimelineUndatedPageResponse undated(TimelineMediaType type, Collection<MediaSubcategory> subcategories,
			TimelineFilter filter, int limit, String encodedCursor) {
		validateLimit(limit);

		TimelineUndatedCursor cursor = encodedCursor == null ? null : cursorCodec.decodeUndated(encodedCursor, type);

		List<TimelineItemProjection> rows = timelineQueryRepository.findUndatedPage(type.fileType(),
				resolveSubcategories(subcategories), filter, cursor == null ? null : cursor.internalId(), limit + 1);

		boolean hasMore = rows.size() > limit;

		List<TimelineItemProjection> visible = hasMore ? rows.subList(0, limit) : rows;

		String nextCursor = hasMore
				? cursorCodec.encodeUndated(new TimelineUndatedCursor(visible.getLast().internalId(), type))
				: null;

		return new TimelineUndatedPageResponse(visible.stream().map(this::toResponse).toList(), nextCursor, hasMore);
	}

	private void validateLimit(int limit) {
		if (limit < 1 || limit > 250) {
			throw new IllegalArgumentException("Timeline limit must be between 1 and 250");
		}
	}

	/**
	 * Subcategory whitelist as enum names for the SQL {@code IN} clause. An absent
	 * or empty selection means "no restriction", so every subcategory is included -
	 * the query never receives an empty list.
	 */
	private List<String> resolveSubcategories(Collection<MediaSubcategory> subcategories) {
		Collection<MediaSubcategory> effective = subcategories == null || subcategories.isEmpty()
				? EnumSet.allOf(MediaSubcategory.class)
				: subcategories;

		return effective.stream().map(MediaSubcategory::name).toList();
	}

	private TimelineItemResponse toResponse(TimelineItemProjection item) {
		return new TimelineItemResponse(item.publicId(), item.fileName(), item.fileType(), item.captureDate(),
				item.dateSource(), item.width(), item.height(), item.durationSeconds(),
				"/api/media/" + item.publicId() + "/thumbnail?w=320", item.location());
	}

	@Transactional(readOnly = true)
	public TimelineIndex index(FileType fileType, Collection<MediaSubcategory> subcategories, TimelineFilter filter) {
		validateFileType(fileType);

		List<String> names = resolveSubcategories(subcategories);

		TimelineCountSummary summary = timelineQueryRepository.findCountSummary(fileType, names, filter);

		Map<Integer, List<TimelineMonthCount>> monthsByYear = new LinkedHashMap<>();

		timelineQueryRepository.findMonthCounts(fileType, names, filter)
				.forEach(month -> monthsByYear.computeIfAbsent(month.year(), _ -> new ArrayList<>()).add(month));

		List<TimelineYearCount> years = monthsByYear.entrySet().stream().map(entry -> {
			List<TimelineMonthCount> months = entry.getValue();

			long count = months.stream().mapToLong(TimelineMonthCount::count).sum();

			return new TimelineYearCount(entry.getKey(), count, months);
		}).toList();

		return new TimelineIndex(summary.totalItems(), summary.datedItems(), summary.undatedItems(), years);
	}

	private void validateFileType(FileType fileType) {
		if (fileType != null && fileType != FileType.PHOTO && fileType != FileType.VIDEO) {
			throw new IllegalArgumentException("Timeline supports only photo or video filters");
		}
	}
}