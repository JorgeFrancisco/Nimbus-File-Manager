package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Guards the shared navigation pattern on server-paginated application screens.
 */
class PaginationTemplateTest {

	@Test
	void paginatedScreensOfferTheCompleteNavigationInAStickyToolbar() throws Exception {
		for (String page : List.of("quarantine", "duplicates", "users", "organization")) {
			String html = Files.readString(Path.of("src/main/resources/templates/app/" + page + ".html"));

			assertThat(html).as(page).contains("pagination-toolbar", "#{pagination.first}", "#{pagination.previous}",
					"#{pagination.next}", "#{pagination.last}");
		}
	}

	@Test
	void quarantineOffersAllSupportedPageSizes() throws Exception {
		String controller = Files.readString(Path.of(
				"src/main/java/br/com/jorgemelo/nimbusfilemanager/quarantine/infrastructure/web/QuarantineWebController.java"));
		String html = Files.readString(Path.of("src/main/resources/templates/app/quarantine.html"));

		assertThat(controller).contains("List.of(50, 100, 200)", "PAGE_SIZE_KEY");
		assertThat(html).contains("th:each=\"option : ${pageSizes}\"", "name=\"size\"");
	}

	@Test
	void languagePanelKeepsSpacingFromAppearancePanel() throws Exception {
		String html = Files.readString(Path.of("src/main/resources/templates/app/settings.html")).replace("\r\n", "\n");

		assertThat(html).contains(
				"<section class=\"panel section\" th:if=\"${activeTab == 'preferences'}\">\n\t\t<h2 th:text=\"#{preferences.language.label}\"")
				.contains("th:if=\"${activeTab == 'preferences'}\" class=\"stack section\"");
	}

	@Test
	void absentQuarantineItemsKeepTheirAlignmentWithoutAnOpenAction() throws Exception {
		String html = Files.readString(Path.of("src/main/resources/templates/app/quarantine.html"));

		assertThat(html).contains("th:if=\"${item.presentInQuarantine()}\"", "quarantine-media-absent")
				.doesNotContain("th:if=\"${item.presentInQuarantine()}\" th:replace=");
	}
}