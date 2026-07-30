package br.com.jorgemelo.nimbusfilemanager.catalog.application;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.catalog.application.dto.CatalogExport;
import br.com.jorgemelo.nimbusfilemanager.catalog.domain.enums.CatalogExportFormat;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.projection.CatalogExportRow;

/**
 * Streams the whole {@code catalog_file} catalog as CSV or JSON.
 *
 * <p>
 * Rows are pulled in keyset-paginated pages and written straight to the
 * response stream, so a 200k-file catalog is exported with bounded memory (one
 * page at a time) and never materialized as a single list. Each page is its own
 * short read transaction (the repository call); the projection carries no lazy
 * association, so writing happens safely outside any transaction.
 */
@Service
public class CatalogExportService {

	private static final int PAGE_SIZE = 1_000;

	private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private static final String[] CSV_HEADERS = { "publicId", "fileKey", "fileName", "extension", "sizeBytes", "sha256",
			"md5", "mimeType", "fileType", "lifecycleStatus", "createdAt", "modifiedAt", "importedAt", "currentPath",
			"originalPath" };

	private final CatalogFileRepository catalogFileRepository;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public CatalogExportService(CatalogFileRepository catalogFileRepository, ObjectMapper objectMapper, Clock clock) {
		this.catalogFileRepository = catalogFileRepository;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	public CatalogExport export(CatalogExportFormat format) {
		String base = "catalog-" + LocalDateTime.now(clock).format(TIMESTAMP_FORMAT);

		return switch (format) {
		case CSV -> new CatalogExport(base + ".csv", "text/csv; charset=UTF-8", this::writeCsv);
		case JSON -> new CatalogExport(base + ".json", "application/json", this::writeJson);
		};
	}

	private void writeCsv(OutputStream outputStream) throws IOException {
		Writer writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);

		writer.write(String.join(",", CSV_HEADERS));
		writer.write("\r\n");

		long lastId = 0;

		List<CatalogExportRow> rows;

		while (!(rows = nextPage(lastId)).isEmpty()) {
			for (CatalogExportRow row : rows) {
				writeCsvRow(writer, row);
			}

			lastId = rows.getLast().id();
		}

		writer.flush();
	}

	private void writeCsvRow(Writer writer, CatalogExportRow row) throws IOException {
		writer.write(String.join(",", csv(row.publicId()), csv(row.fileKey()), csv(row.fileName()),
				csv(row.extension()), csv(row.sizeBytes()), csv(row.sha256()), csv(row.md5()), csv(row.mimeType()),
				csv(row.fileType()), csv(row.lifecycleStatus()), csv(row.createdAt()), csv(row.modifiedAt()),
				csv(row.importedAt()), csv(row.currentPath()), csv(row.originalPath())));

		writer.write("\r\n");
	}

	private void writeJson(OutputStream outputStream) throws IOException {
		try (JsonGenerator generator = objectMapper.getFactory().createGenerator(outputStream)) {
			generator.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);

			generator.writeStartArray();

			long lastId = 0;

			List<CatalogExportRow> rows;

			while (!(rows = nextPage(lastId)).isEmpty()) {
				for (CatalogExportRow row : rows) {
					generator.writeObject(row);
				}

				lastId = rows.getLast().id();
			}

			generator.writeEndArray();
			generator.flush();
		}
	}

	private List<CatalogExportRow> nextPage(long lastId) {
		return catalogFileRepository.findCatalogExportRows(lastId, PageRequest.of(0, PAGE_SIZE));
	}

	private String csv(Object value) {
		if (value == null) {
			return "";
		}

		String text = Objects.toString(value);

		if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
			return "\"" + text.replace("\"", "\"\"") + "\"";
		}

		return text;
	}
}