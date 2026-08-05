package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationItem;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPreviewExport;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.StoredPlanPage;

/**
 * Builds the ZIP file streamed by
 * {@code POST /api/organization/preview/export}. Extracted out of
 * {@code OrganizationController} so the streaming/JSON-serialization logic is a
 * unit-testable collaborator instead of only reachable end-to-end through the
 * controller.
 */
@Service
public class OrganizationPreviewExportService {

	private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	/** Rows per read, so an export never holds the whole plan. */
	private static final int PAGE_SIZE = 500;

	private final OrganizationService organizationService;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public OrganizationPreviewExportService(OrganizationService organizationService, ObjectMapper objectMapper,
			Clock clock) {
		this.organizationService = organizationService;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	public OrganizationPreviewExport export(UUID executionId) {
		String timestamp = LocalDateTime.now(clock).format(TIMESTAMP_FORMAT);
		String jsonFileName = "organization-preview-" + timestamp + ".json";
		String zipFileName = "organization-preview-" + timestamp + ".zip";

		StreamingResponseBody body = outputStream -> writeZip(executionId, jsonFileName, outputStream);

		return new OrganizationPreviewExport(zipFileName, body);
	}

	/**
	 * Streams the published plan, page by page.
	 *
	 * <p>
	 * Reading it in pages is not an optimization of the download - it is what keeps
	 * a plan of a hundred thousand items from being assembled in memory to be
	 * serialized. Each page is written and dropped.
	 */
	private void writeZip(UUID executionId, String jsonFileName, OutputStream outputStream) throws IOException {
		try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
			zipOutputStream.setLevel(Deflater.BEST_COMPRESSION);

			ZipEntry entry = new ZipEntry(jsonFileName);

			zipOutputStream.putNextEntry(entry);

			JsonGenerator generator = objectMapper.getFactory().createGenerator(zipOutputStream);

			generator.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);

			writePlan(executionId, generator);

			generator.flush();

			zipOutputStream.closeEntry();
			zipOutputStream.finish();
		}
	}
	private void writePlan(UUID executionId, JsonGenerator generator) throws IOException {
		StoredPlanPage first = organizationService.planPagePublic(executionId, 0, PAGE_SIZE, false)
				.orElseThrow(() -> new IllegalStateException(
						"There is no published organization plan for execution " + executionId));

		generator.setCodec(objectMapper);

		generator.writeStartObject();
		generator.writeStringField("sourcePath", first.sourcePath());
		generator.writeStringField("targetPath", first.targetPath());
		generator.writeStringField("layout", first.layout() == null ? null : first.layout().name());
		generator.writeBooleanField("catalogChanged", first.catalogChanged());
		generator.writeObjectField("summary", first.summary());

		generator.writeArrayFieldStart("items");

		StoredPlanPage current = first;
		int page = 0;

		while (!current.items().isEmpty()) {
			for (OrganizationItem item : current.items()) {
				generator.writeObject(item);
			}

			page++;

			if ((long) page * PAGE_SIZE >= current.totalItems()) {
				break;
			}

			current = organizationService.planPagePublic(executionId, page, PAGE_SIZE, false).orElseThrow(
					() -> new IllegalStateException("The organization plan of execution " + executionId
							+ " stopped being readable while it was being exported"));
		}

		generator.writeEndArray();
		generator.writeEndObject();
	}
}