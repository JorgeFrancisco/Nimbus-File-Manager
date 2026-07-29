package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Guards area-only polling on the long-running duplicate-screen jobs. */
class DuplicatesTemplateTest {

	@Test
	void visibleDuplicateScreenCopyUsesMessageBundleKeys() throws Exception {
		String html = Files.readString(Path.of("src/main/resources/templates/app/duplicates.html"));

		assertThat(html).contains("th:lang=\"${#locale.toLanguageTag()}\"", "common(#{duplicates.title}, 'duplicates')",
				"app(~{::section}, #{duplicates.title})", "th:text=\"#{duplicates.tab.exact}\"",
				"th:text=\"#{duplicates.tab.similar}\"", "th:text=\"#{duplicates.tab.videos}\"",
				"#{duplicates.rebuild.confirm}", "#{duplicates.rebuild.confirm.videos}",
				"#{duplicates.progress(${#numbers.formatPercent(similarityPercent / 100.0, 1, 2)})}",
				"#{duplicates.progress(${#numbers.formatPercent(phashPercent / 100.0, 1, 2)})}",
				"th:text=\"#{duplicates.delete.confirm.title}\"", "th:text=\"#{duplicates.folder.clear.description}\"",
				"id=\"clearSelectionButton\"", "th:text=\"#{duplicates.selection.clear}\"",
				"th:title=\"#{duplicates.selection.clear.title}\"")
				.doesNotContain("${phashFailed} + ' foto(s)", "aria-label=|Progresso:",
				"th:text=\"|${totalElements} grupo(s)|\"");
	}

	@Test
	void progressPanelsRefreshOnlyTheirOwnRegion() throws Exception {
		String html = Files.readString(Path.of("src/main/resources/templates/app/duplicates.html"));
		String javascript = Files.readString(Path.of("src/main/resources/static/js/pages/duplicates.js"));

		assertThat(html).contains("id=\"duplicatesInventoryProgress\"").contains("id=\"fingerprintProgressRegion\"")
				.contains("id=\"similarityProgressRegion\"").contains("data-refresh-ms=\"4000\"")
				.contains("data-refresh-ms=\"3000\"").contains("data-refresh-ms=${phashBlocking ? 4000 : null}")
				.doesNotContain("window.location.reload()").contains("fingerprint-failures-open")
				.contains("id=\"fingerprintFailuresDialog\"").contains("id=\"fingerprintFailuresRows\"")
				// The dialog is shared by the tabs, so the list it loads is rendered into it.
				// Hard-coding the photo endpoint in the script made the Videos tab show photos.
				.contains("data-failures-url=${failuresUrl}");
		assertThat(javascript).contains("failuresDialog.dataset.failuresUrl")
				.contains("let failuresLoaded = false")
				// A deletion updates the current page in place (rows/groups removed
				// client-side) as the
				// primary path; a full reload is only the fallback when removing groups empties
				// the page, so
				// the following groups are pulled in. The long-running progress panels still
				// never reload
				// (they refresh their own region via data-refresh-ms), which the template
				// assertions above
				// guard, and a reload can never happen while a progress panel is polling
				// because deletion is
				// unavailable in that blocked state.
				.contains("removeDeletedRows(ids)")
				// The deletion is async: the screen polls the progress endpoint for "Movendo X
				// de N" instead
				// of blocking on the POST, so a slow move never freezes the page.
				.contains("/app/duplicates/delete/progress").contains("pollDeleteProgress(ids)");
		// The move also drives a visual bar, not just the "Movendo X de N" text.
		assertThat(html).contains("id=\"deleteProgressBar\"");
		assertThat(javascript).contains("setDeleteProgress(");
	}

	@Test
	void longFileNamesStayInsideTheFileColumn() throws Exception {
		String css = Files.readString(Path.of("src/main/resources/static/css/pages/duplicates.css"));

		assertThat(css).contains(".duplicate-details-table td:nth-child(2) .media-preview-link",
				"overflow-wrap: anywhere", "word-break: break-word", "flex: 0 0 34px");
	}

	@Test
	void toolbarSizeAndTypeControlsAlignWithTheViewSwitchButtons() throws Exception {
		String css = Files.readString(Path.of("src/main/resources/static/css/pages/duplicates.css"));

		// The view-switch buttons are 38px tall; without these rules the page-size
		// select and the "Tipo" filter had no explicit height and rode higher, breaking
		// the toolbar's vertical line.
		assertThat(css).contains("align-items: center;", ".explorer-size select {",
				".duplicates-type-filter > summary {",
				"height: 38px;");
	}

	@Test
	void clearSelectionClearsThePersistedCrossPageSelection() throws Exception {
		String javascript = Files.readString(Path.of("src/main/resources/static/js/pages/duplicates.js"));

		assertThat(javascript).contains("const clearSelectionButton = document.getElementById('clearSelectionButton')",
				"Object.keys(selection.selected).forEach((id) => selection.deselected.add(id))",
				"delete selection.selected[id]", "selection.deselected.add(id)", "groupToggle.checked = false",
				"previewList.replaceChildren()");
	}

	@Test
	void folderBulkSelectionUsesSuggestionSafetyAndNeverCompletesAGroup() throws Exception {
		String javascript = Files.readString(Path.of("src/main/resources/static/js/pages/duplicates.js"));

		assertThat(javascript).contains("function isDeletionCandidate(checkbox)",
				"checkbox.checked = inFolder(checkbox);", "checkbox.checked = isDeletionCandidate(checkbox)",
				"const recommendedKeep = groupCheckboxes.find((checkbox) => !isDeletionCandidate(checkbox))",
				"groupCheckboxes.every((checkbox) => checkbox.checked)", "recommendedKeep.checked = false");
	}
}