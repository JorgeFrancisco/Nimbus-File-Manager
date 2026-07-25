package br.com.jorgemelo.nimbusfilemanager.organization.application;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPlan;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPreviewExport;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPreviewRequest;

/**
 * Builds the ZIP file streamed by
 * {@code POST /api/organization/preview/export}. Extracted out of
 * {@code OrganizationController} so the streaming/JSON-serialization logic is a
 * unit-testable collaborator instead of
 * only reachable end-to-end through the controller.
 */
@Service
public class OrganizationPreviewExportService {

	private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private final OrganizationService organizationService;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public OrganizationPreviewExportService(OrganizationService organizationService, ObjectMapper objectMapper,
			Clock clock) {
		this.organizationService = organizationService;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	public OrganizationPreviewExport export(OrganizationPreviewRequest request) {
		String timestamp = LocalDateTime.now(clock).format(TIMESTAMP_FORMAT);
		String jsonFileName = "organization-preview-" + timestamp + ".json";
		String zipFileName = "organization-preview-" + timestamp + ".zip";

		StreamingResponseBody body = outputStream -> writeZip(request, jsonFileName, outputStream);

		return new OrganizationPreviewExport(zipFileName, body);
	}

	private void writeZip(OrganizationPreviewRequest request, String jsonFileName, OutputStream outputStream)
			throws IOException {
		OrganizationPlan plan = organizationService.preview(request);

		try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
			zipOutputStream.setLevel(Deflater.BEST_COMPRESSION);

			ZipEntry entry = new ZipEntry(jsonFileName);

			zipOutputStream.putNextEntry(entry);

			JsonGenerator generator = objectMapper.getFactory().createGenerator(zipOutputStream);

			generator.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);

			objectMapper.writerWithDefaultPrettyPrinter().writeValue(generator, plan);

			generator.flush();

			zipOutputStream.closeEntry();
			zipOutputStream.finish();
		}
	}
}