package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPlan;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPreviewExport;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPreviewRequest;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationSummary;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;

/**
 * Unit coverage for the ZIP-building logic extracted out of
 * OrganizationController. ControllersTest still exercises
 * the same flow end-to-end through the controller; this class targets the
 * service in isolation.
 */
class OrganizationPreviewExportServiceTest {

	@Test
	void exportShouldBuildZipFileNameAndStreamPreviewAsJsonEntry() throws Exception {
		OrganizationService organizationService = mock(OrganizationService.class);

		OrganizationPreviewExportService service = new OrganizationPreviewExportService(organizationService,
				new ObjectMapper(), Clock.systemDefaultZone());

		OrganizationPreviewRequest request = new OrganizationPreviewRequest("C:/input", "C:/target", true,
				OrganizationLayout.DEFAULT, 100, null, null, null, null, null, null, null);

		OrganizationPlan plan = new OrganizationPlan("C:/input", "C:/target", OrganizationLayout.DEFAULT, false,
				new OrganizationSummary(1, 1, 0, 0, 1, 0, 0, 0, 0), List.of());

		when(organizationService.preview(request)).thenReturn(plan);

		OrganizationPreviewExport export = service.export(request);

		Assertions.assertThat(export.zipFileName()).startsWith("organization-preview-").endsWith(".zip");

		ByteArrayOutputStream output = new ByteArrayOutputStream();

		export.body().writeTo(output);

		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
			ZipEntry entry = zip.getNextEntry();

			Assertions.assertThat(entry.getName()).startsWith("organization-preview-").endsWith(".json");
			Assertions.assertThat(new String(zip.readAllBytes())).contains("\"sourcePath\"", "C:/input");
			Assertions.assertThat(zip.getNextEntry()).isNull();
		}
	}
}