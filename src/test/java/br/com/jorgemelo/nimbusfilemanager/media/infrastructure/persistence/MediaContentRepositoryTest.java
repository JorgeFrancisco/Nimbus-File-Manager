package br.com.jorgemelo.nimbusfilemanager.media.infrastructure.persistence;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import br.com.jorgemelo.nimbusfilemanager.media.application.dto.MediaContentSource;

@ExtendWith(MockitoExtension.class)
class MediaContentRepositoryTest {

	@Mock
	private NamedParameterJdbcTemplate jdbcTemplate;

	@Test
	void contentShouldAcceptActiveAndQuarantinedFiles() {
		when(jdbcTemplate.query(any(String.class), any(SqlParameterSource.class),
				ArgumentMatchers.<RowMapper<MediaContentSource>>any())).thenReturn(List.of());

		new MediaContentRepository(jdbcTemplate, new LocationLabels()).findContent(UUID.randomUUID());

		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

		verify(jdbcTemplate).query(sql.capture(), any(SqlParameterSource.class),
				ArgumentMatchers.<RowMapper<MediaContentSource>>any());

		Assertions.assertThat(sql.getValue()).contains("mf.lifecycle_status IN ('ACTIVE', 'DELETED')")
				.doesNotContain("mf.file_type IN ('PHOTO', 'VIDEO')", "JOIN media_metadata");
	}
}