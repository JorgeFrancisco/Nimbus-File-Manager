package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationItem;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationPreviewExport;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationSummary;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.StoredPlanPage;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;

/**
 * The export streams what was published rather than building a second plan.
 *
 * <p>
 * That is the difference worth holding: an export used to recalculate the whole
 * library at download time, so the file could describe a plan the user never
 * saw. It reads the stored rows now, in pages, so it describes exactly the plan
 * the screen showed and never holds a hundred thousand items to serialize them.
 */
class OrganizationPreviewExportServiceTest {

	private final OrganizationService organizationService = mock(OrganizationService.class);

	private final OrganizationPreviewExportService service = new OrganizationPreviewExportService(organizationService,
			new ObjectMapper(), Clock.systemDefaultZone());

	@Test
	void theZipCarriesThePublishedPlanAsASingleJsonEntry() throws Exception {
		UUID executionId = UUID.randomUUID();

		when(organizationService.planPagePublic(eq(executionId), anyInt(), anyInt(),
				anyBoolean())).thenReturn(Optional.of(page(List.of(item("a.jpg")), 1)));

		OrganizationPreviewExport export = service.export(executionId);

		Assertions.assertThat(export.zipFileName()).startsWith("organization-preview-").endsWith(".zip");

		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytesOf(export)))) {
			ZipEntry entry = zip.getNextEntry();

			Assertions.assertThat(entry.getName()).startsWith("organization-preview-").endsWith(".json");

			String json = new String(zip.readAllBytes());

			Assertions.assertThat(json).contains("\"sourcePath\"", "C:/input", "\"items\"", "a.jpg");
			Assertions.assertThat(zip.getNextEntry()).isNull();
		}
	}

	/**
	 * A plan larger than one page is read page by page. What this pins is that the
	 * export never asks for the whole plan at once - the reason it can survive a
	 * plan at the item cap.
	 */
	@Test
	void aPlanLargerThanOnePageIsStreamedInSeveralReads() throws Exception {
		UUID executionId = UUID.randomUUID();

		List<OrganizationItem> first = new ArrayList<>();
		List<OrganizationItem> second = new ArrayList<>();

		for (int index = 0; index < 500; index++) {
			first.add(item("first-" + index + ".jpg"));
		}

		second.add(item("second.jpg"));

		when(organizationService.planPagePublic(executionId, 0, 500, false))
				.thenReturn(Optional.of(page(first, 501)));
		when(organizationService.planPagePublic(executionId, 1, 500, false))
				.thenReturn(Optional.of(page(second, 501)));

		String json = jsonOf(service.export(executionId));

		Assertions.assertThat(json).contains("first-0.jpg", "first-499.jpg", "second.jpg");

		verify(organizationService, times(2)).planPagePublic(eq(executionId), anyInt(),
				anyInt(), anyBoolean());
	}

	/**
	 * Exporting a plan that is not there fails rather than producing an empty ZIP
	 * that looks like a library with nothing to organize.
	 */
	@Test
	void exportingAPlanThatIsNoLongerThereFails() {
		UUID executionId = UUID.randomUUID();

		when(organizationService.planPagePublic(eq(executionId), anyInt(), anyInt(),
				anyBoolean())).thenReturn(Optional.empty());

		OrganizationPreviewExport export = service.export(executionId);

		Assertions.assertThatThrownBy(() -> bytesOf(export)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(executionId.toString());
	}

	/**
	 * A plan can expire while it is being downloaded. The export stops saying so
	 * rather than finishing with half a plan that looks like the whole one.
	 */
	@Test
	void aPlanThatExpiresMidExportStopsRatherThanTruncating() {
		UUID executionId = UUID.randomUUID();

		List<OrganizationItem> first = new ArrayList<>();

		for (int index = 0; index < 500; index++) {
			first.add(item("first-" + index + ".jpg"));
		}

		when(organizationService.planPagePublic(executionId, 0, 500, false))
				.thenReturn(Optional.of(page(first, 501)));
		when(organizationService.planPagePublic(executionId, 1, 500, false)).thenReturn(Optional.empty());

		OrganizationPreviewExport export = service.export(executionId);

		Assertions.assertThatThrownBy(() -> bytesOf(export)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(executionId.toString());
	}

	private byte[] bytesOf(OrganizationPreviewExport export) throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream();

		export.body().writeTo(output);

		return output.toByteArray();
	}

	private String jsonOf(OrganizationPreviewExport export) throws Exception {
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytesOf(export)))) {
			zip.getNextEntry();

			return new String(zip.readAllBytes());
		}
	}

	private StoredPlanPage page(List<OrganizationItem> items, int totalItems) {
		return new StoredPlanPage("C:/input", "C:/target", OrganizationLayout.DEFAULT,
				new OrganizationSummary(totalItems, 0, 0, 0, totalItems, 0, 0, 0, 0), false, items, 0, 500,
				totalItems);
	}

	private OrganizationItem item(String fileName) {
		return new OrganizationItem(null, UUID.randomUUID(), fileName, "C:/input/" + fileName,
				"C:/target/" + fileName, null, null, null, null, null, null, null, 10L, false, false, false, false,
				false, null, null, null);
	}
}