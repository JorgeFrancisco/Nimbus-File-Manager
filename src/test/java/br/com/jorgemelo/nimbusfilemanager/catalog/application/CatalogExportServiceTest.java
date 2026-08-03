package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import br.com.jorgemelo.nimbusfilemanager.catalog.domain.enums.CatalogExportFormat;
import br.com.jorgemelo.nimbusfilemanager.catalog.domain.repository.projection.CatalogExportRow;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;

@ExtendWith(MockitoExtension.class)
class CatalogExportServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, Month.JULY, 13, 10, 0);

	@Mock
	private CatalogFileRepository catalogFileRepository;

	private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

	@Test
	void csvExportShouldWriteHeaderEscapedRowsAcrossPagesAndOmitInternalId() throws Exception {
		CatalogExportRow first = row(1L, "D:/a,b.jpg", "photo,1.jpg");
		CatalogExportRow second = row(2L, "D:/plain.jpg", "photo2.jpg");
		CatalogExportRow third = row(3L, "D:/third.jpg", "photo3.jpg");

		// Two pages: (1,2) then (3), then empty - proves keyset advances by last id.
		when(catalogFileRepository.findCatalogExportRows(eq(0L), any())).thenReturn(List.of(first, second));
		when(catalogFileRepository.findCatalogExportRows(eq(2L), any())).thenReturn(List.of(third));
		when(catalogFileRepository.findCatalogExportRows(eq(3L), any())).thenReturn(List.of());

		String csv = consume(service().export(CatalogExportFormat.CSV).body());

		String[] lines = csv.split("\r\n");
		Assertions.assertThat(lines).hasSize(4);
		Assertions.assertThat(lines[0])
				.isEqualTo("publicId,fileKey,fileName,extension,sizeBytes,sha256,md5,mimeType,fileType,"
						+ "lifecycleStatus,createdAt,modifiedAt,importedAt,currentPath,originalPath");
		// Comma-bearing values are quoted; the internal id never appears.
		Assertions.assertThat(lines[1]).contains("\"D:/a,b.jpg\"", "\"photo,1.jpg\"");
		Assertions.assertThat(lines[2]).contains("D:/plain.jpg", "photo2.jpg");
		Assertions.assertThat(lines[3]).contains("D:/third.jpg");
	}

	@Test
	void csvExportShouldEscapeQuotesAndLeaveNullFieldsEmpty() throws Exception {
		CatalogExportRow quoted = new CatalogExportRow(1L, UUID.fromString("00000000-0000-0000-0000-000000000001"),
				"D:/say \"hi\".jpg", "hi.jpg", "jpg", 10L, null, null, "image/jpeg", "PHOTO", "ACTIVE", NOW, NOW, NOW,
				"D:/say \"hi\".jpg", "D:/orig.jpg");

		when(catalogFileRepository.findCatalogExportRows(eq(0L), any())).thenReturn(List.of(quoted));
		when(catalogFileRepository.findCatalogExportRows(eq(1L), any())).thenReturn(List.of());

		String csv = consume(service().export(CatalogExportFormat.CSV).body());

		String dataLine = csv.split("\r\n")[1];
		// Internal quotes doubled and the value wrapped; null sha256/md5 become empty
		// fields.

		Assertions.assertThat(dataLine).contains("\"D:/say \"\"hi\"\".jpg\"").contains(",,image/jpeg");
	}

	@Test
	void jsonExportShouldWriteAnArrayWithoutTheInternalId() throws Exception {
		when(catalogFileRepository.findCatalogExportRows(eq(0L), any()))
				.thenReturn(List.of(row(1L, "D:/a.jpg", "a.jpg"), row(2L, "D:/b.jpg", "b.jpg")));
		when(catalogFileRepository.findCatalogExportRows(eq(2L), any())).thenReturn(List.of());

		String json = consume(service().export(CatalogExportFormat.JSON).body());

		List<Map<String, Object>> parsed = objectMapper.readValue(json,
				objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));

		Assertions.assertThat(parsed).hasSize(2);
		Assertions.assertThat(parsed.get(0)).containsKey("publicId").containsKey("fileKey").doesNotContainKey("id");
		Assertions.assertThat(parsed.get(0)).containsEntry("fileKey", "D:/a.jpg");
	}

	@Test
	void exportShouldNameTheFileByFormat() {
		Assertions.assertThat(service().export(CatalogExportFormat.CSV).fileName()).endsWith(".csv");
		Assertions.assertThat(service().export(CatalogExportFormat.JSON).fileName()).endsWith(".json");
		Assertions.assertThat(service().export(CatalogExportFormat.CSV).contentType()).contains("text/csv");
	}

	private CatalogExportService service() {
		return new CatalogExportService(catalogFileRepository, objectMapper, Clock.systemDefaultZone());
	}

	private CatalogExportRow row(Long id, String fileKey, String fileName) {
		return new CatalogExportRow(id, UUID.fromString("00000000-0000-0000-0000-00000000000" + id), fileKey, fileName,
				"jpg", 100L, "sha" + id, "md" + id, "image/jpeg", "PHOTO", "ACTIVE", NOW, NOW, NOW, fileKey,
				"D:/orig.jpg");
	}

	private String consume(StreamingResponseBody body) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		body.writeTo(out);

		return out.toString(StandardCharsets.UTF_8);
	}
}