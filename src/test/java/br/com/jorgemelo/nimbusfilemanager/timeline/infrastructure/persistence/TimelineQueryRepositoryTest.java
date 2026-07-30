package br.com.jorgemelo.nimbusfilemanager.timeline.infrastructure.persistence;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import br.com.jorgemelo.nimbusfilemanager.geolocation.application.LocationLabels;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineCountSummary;
import br.com.jorgemelo.nimbusfilemanager.timeline.application.dto.TimelineMonthCount;
import br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection.TimelineItemProjection;

@ExtendWith(MockitoExtension.class)
class TimelineQueryRepositoryTest {

	private static final List<String> SUBS = List.of("CAMERA", "CELLPHONE");

	@Mock
	private NamedParameterJdbcTemplate jdbcTemplate;

	@Test
	void shouldUseStableKeysetPaginationAndOnlyActivePhotosAndVideos() {
		LocalDateTime cursorDate = LocalDateTime.of(2026, Month.JULY, 11, 14, 30);

		when(jdbcTemplate.query(any(String.class), any(SqlParameterSource.class),
				ArgumentMatchers.<RowMapper<TimelineItemProjection>>any())).thenReturn(List.of());

		repository().findPage(FileType.PHOTO, SUBS, cursorDate, 91L, 121);

		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<SqlParameterSource> parameters = ArgumentCaptor.forClass(SqlParameterSource.class);

		verify(jdbcTemplate).query(sql.capture(), parameters.capture(),
				ArgumentMatchers.<RowMapper<TimelineItemProjection>>any());

		Assertions.assertThat(sql.getValue())
				.contains("mf.lifecycle_status = 'ACTIVE'", "mf.file_type IN ('PHOTO', 'VIDEO')",
						"m.capture_date IS NOT NULL", "CAST(:fileType AS varchar)",
						"m.subcategory IN (:subcategories)", "CAST(:cursorDate AS timestamp) IS NULL",
						"CAST(:cursorId AS bigint)", "ORDER BY m.capture_date DESC, mf.id DESC", "LIMIT :limit")
				.doesNotContain("OFFSET", "media_location", "metadata_json", "m.capture_date < :cursorDate OR");
		Assertions.assertThat(parameters.getValue().getValue("fileType")).isEqualTo("PHOTO");
		Assertions.assertThat(parameters.getValue().getValue("subcategories")).isEqualTo(SUBS);
		Assertions.assertThat(parameters.getValue().getValue("cursorDate")).isEqualTo(Timestamp.valueOf(cursorDate));
		Assertions.assertThat(parameters.getValue().getValue("cursorId")).isEqualTo(91L);
		Assertions.assertThat(parameters.getValue().getValue("limit")).isEqualTo(121);
		Assertions.assertThat(parameters.getValue().getSqlType("fileType")).isEqualTo(Types.VARCHAR);
		Assertions.assertThat(parameters.getValue().getSqlType("cursorDate")).isEqualTo(Types.TIMESTAMP);
		Assertions.assertThat(parameters.getValue().getSqlType("cursorId")).isEqualTo(Types.BIGINT);
	}

	@Test
	void shouldMapAProjectionWithoutLoadingEntities() throws Exception {
		UUID publicId = UUID.randomUUID();

		LocalDateTime captureDate = LocalDateTime.of(2026, Month.JULY, 11, 14, 32);

		ResultSet resultSet = mock(ResultSet.class);

		when(resultSet.getLong("internal_id")).thenReturn(42L);
		when(resultSet.getObject("public_id", UUID.class)).thenReturn(publicId);
		when(resultSet.getString("file_name")).thenReturn("IMG_0042.JPG");
		when(resultSet.getString("file_type")).thenReturn("PHOTO");
		when(resultSet.getTimestamp("capture_date")).thenReturn(Timestamp.valueOf(captureDate));
		when(resultSet.getString("date_source")).thenReturn("EXIF");
		when(resultSet.getObject("display_width")).thenReturn(4032);
		when(resultSet.getObject("display_height")).thenReturn(3024);
		when(resultSet.getObject("duration_seconds")).thenReturn(null);

		when(jdbcTemplate.query(any(String.class), any(SqlParameterSource.class),
				ArgumentMatchers.<RowMapper<TimelineItemProjection>>any())).thenAnswer(invocation -> {
					RowMapper<TimelineItemProjection> mapper = invocation.getArgument(2);
					return List.of(mapper.mapRow(resultSet, 0));
				});

		List<TimelineItemProjection> page = repository().findPage(null, SUBS, null, null,
				120);

		Assertions.assertThat(page).containsExactly(new TimelineItemProjection(42L, publicId, "IMG_0042.JPG",
				FileType.PHOTO, captureDate, DateSource.EXIF, 4032, 3024, null));
	}

	@Test
	void shouldMapPostgresRealDurationWithoutAssumingDouble() throws Exception {
		ResultSet resultSet = mock(ResultSet.class);

		when(resultSet.getLong("internal_id")).thenReturn(43L);
		when(resultSet.getObject("public_id", UUID.class)).thenReturn(UUID.randomUUID());
		when(resultSet.getString("file_name")).thenReturn("clip.mp4");
		when(resultSet.getString("file_type")).thenReturn("VIDEO");
		when(resultSet.getTimestamp("capture_date")).thenReturn(Timestamp.valueOf(LocalDateTime.now()));
		when(resultSet.getString("date_source")).thenReturn("MEDIA_INFO");
		when(resultSet.getObject("duration_seconds")).thenReturn(92.5F);
		when(jdbcTemplate.query(any(String.class), any(SqlParameterSource.class),
				ArgumentMatchers.<RowMapper<TimelineItemProjection>>any())).thenAnswer(invocation -> {
					RowMapper<TimelineItemProjection> mapper = invocation.getArgument(2);
					return List.of(mapper.mapRow(resultSet, 0));
				});

		List<TimelineItemProjection> page = repository().findPage(null, SUBS, null, null,
				120);

		Assertions.assertThat(page.getFirst().durationSeconds()).isEqualTo(92.5D);
	}

	@Test
	void shouldRejectPartialCursorAndInvalidLimit() {
		TimelineQueryRepository repository = repository();

		Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> repository.findPage(null, SUBS, LocalDateTime.now(), null, 120));
		Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> repository.findPage(null, SUBS, null, 42L, 120));
		Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> repository.findPage(null, SUBS, null, null, 0));
	}

	@Test
	void shouldRejectAnEmptySubcategoryFilter() {
		TimelineQueryRepository repository = repository();

		Assertions.assertThatIllegalArgumentException()
				.isThrownBy(() -> repository.findPage(null, List.of(), null, null, 120))
				.withMessageContaining("subcategory");
	}

	@Test
	void shouldBindNullOptionalParametersWithExplicitPostgresTypes() {
		when(jdbcTemplate.query(any(String.class), any(SqlParameterSource.class),
				ArgumentMatchers.<RowMapper<TimelineItemProjection>>any())).thenReturn(List.of());

		repository().findPage(null, SUBS, null, null, 120);

		ArgumentCaptor<SqlParameterSource> parameters = ArgumentCaptor.forClass(SqlParameterSource.class);

		verify(jdbcTemplate).query(any(String.class), parameters.capture(),
				ArgumentMatchers.<RowMapper<TimelineItemProjection>>any());

		Assertions.assertThat(parameters.getValue().getValue("fileType")).isNull();
		Assertions.assertThat(parameters.getValue().getValue("cursorDate")).isNull();
		Assertions.assertThat(parameters.getValue().getValue("cursorId")).isNull();
		Assertions.assertThat(parameters.getValue().getSqlType("fileType")).isEqualTo(Types.VARCHAR);
		Assertions.assertThat(parameters.getValue().getSqlType("cursorDate")).isEqualTo(Types.TIMESTAMP);
		Assertions.assertThat(parameters.getValue().getSqlType("cursorId")).isEqualTo(Types.BIGINT);
	}

	@Test
	void shouldAggregateOnlyDatedActiveVisualMediaByMonth() throws Exception {
		ResultSet resultSet = mock(ResultSet.class);

		when(resultSet.getInt("year")).thenReturn(2026);
		when(resultSet.getInt("month")).thenReturn(7);
		when(resultSet.getLong("item_count")).thenReturn(1234L);
		when(jdbcTemplate.query(any(String.class), any(SqlParameterSource.class),
				ArgumentMatchers.<RowMapper<TimelineMonthCount>>any())).thenAnswer(invocation -> {
					RowMapper<TimelineMonthCount> mapper = invocation.getArgument(2);
					return List.of(mapper.mapRow(resultSet, 0));
				});

		List<TimelineMonthCount> counts = repository().findMonthCounts(FileType.VIDEO,
				SUBS);

		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<SqlParameterSource> parameters = ArgumentCaptor.forClass(SqlParameterSource.class);

		verify(jdbcTemplate).query(sql.capture(), parameters.capture(),
				ArgumentMatchers.<RowMapper<TimelineMonthCount>>any());

		Assertions.assertThat(sql.getValue())
				.contains("mf.lifecycle_status = 'ACTIVE'", "mf.file_type IN ('PHOTO', 'VIDEO')",
						"m.capture_date IS NOT NULL", "m.subcategory IN (:subcategories)", "GROUP BY m.year, m.month",
						"ORDER BY m.year DESC, m.month DESC")
				.doesNotContain("m.day", "media_location");
		Assertions.assertThat(parameters.getValue().getValue("fileType")).isEqualTo("VIDEO");
		Assertions.assertThat(parameters.getValue().getValue("subcategories")).isEqualTo(SUBS);
		Assertions.assertThat(counts).containsExactly(new TimelineMonthCount(2026, 7, 1234));
	}

	@Test
	void shouldReturnDatedAndUndatedSummary() throws Exception {
		ResultSet resultSet = mock(ResultSet.class);

		when(resultSet.getLong("total_items")).thenReturn(150041L);
		when(resultSet.getLong("dated_items")).thenReturn(150000L);
		when(resultSet.getLong("undated_items")).thenReturn(41L);
		when(jdbcTemplate.query(any(String.class), any(SqlParameterSource.class),
				ArgumentMatchers.<RowMapper<TimelineCountSummary>>any())).thenAnswer(invocation -> {
					RowMapper<TimelineCountSummary> mapper = invocation.getArgument(2);
					return List.of(mapper.mapRow(resultSet, 0));
				});

		TimelineCountSummary summary = repository().findCountSummary(null, SUBS);

		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

		verify(jdbcTemplate).query(sql.capture(), any(SqlParameterSource.class),
				ArgumentMatchers.<RowMapper<TimelineCountSummary>>any());

		Assertions.assertThat(sql.getValue())
				.contains("COUNT(*) AS total_items", "COUNT(m.capture_date) AS dated_items",
						"COUNT(*) - COUNT(m.capture_date) AS undated_items", "m.subcategory IN (:subcategories)")
				.doesNotContain("GROUP BY", "media_location");
		Assertions.assertThat(summary).isEqualTo(new TimelineCountSummary(150041, 150000, 41));
	}

	@Test
	void shouldReturnEmptySummaryDefensively() {
		when(jdbcTemplate.query(any(String.class), any(SqlParameterSource.class),
				ArgumentMatchers.<RowMapper<TimelineCountSummary>>any())).thenReturn(List.of());

		TimelineCountSummary summary = repository().findCountSummary(FileType.PHOTO, SUBS);

		Assertions.assertThat(summary).isEqualTo(new TimelineCountSummary(0, 0, 0));
	}

	private TimelineQueryRepository repository() {
		return new TimelineQueryRepository(jdbcTemplate, new LocationLabels());
	}
}