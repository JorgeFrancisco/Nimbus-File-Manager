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

		assertThat(html)
				.contains("th:lang=\"${#locale.toLanguageTag()}\"", "common(#{duplicates.title}, 'duplicates')",
						"app(~{::section}, #{duplicates.title})", "th:text=\"#{duplicates.tab.exact}\"",
						"th:text=\"#{duplicates.tab.similar}\"", "th:text=\"#{duplicates.tab.videos}\"",
						"#{duplicates.rebuild.confirm}", "#{duplicates.rebuild.confirm.videos}",
						"#{duplicates.similarity.notAnalyzed}", "#{duplicates.similarity.outdated}",
						"#{duplicates.similarity.analyze}",
						"#{duplicates.progress(${#numbers.formatPercent(phashPercent / 100.0, 1, 2)})}",
						"th:text=\"#{duplicates.delete.confirm.title}\"",
						"th:text=\"#{duplicates.folder.clear.description}\"", "id=\"clearSelectionButton\"",
						"th:text=\"#{duplicates.selection.clear}\"", "th:title=\"#{duplicates.selection.clear.title}\"")
				.doesNotContain("${phashFailed} + ' foto(s)", "aria-label=|Progresso:",
						"th:text=\"|${totalElements} grupo(s)|\"");
	}

	@Test
	void progressPanelsRefreshOnlyTheirOwnRegion() throws Exception {
		String html = Files.readString(Path.of("src/main/resources/templates/app/duplicates.html"));
		String javascript = Files.readString(Path.of("src/main/resources/static/js/pages/duplicates.js"));

		assertThat(html).contains("id=\"duplicatesInventoryProgress\"").contains("id=\"fingerprintProgressRegion\"")
				.contains("id=\"similarityProgressRegion\"")
				// The two panels that carried no changing data of their own now watch the
				// activity poll the app shell already runs, instead of asking the server to
				// render this entire screen every few seconds. On a large library that render
				// costs seconds, and the cycles stacked: a real navigation showed the document
				// at 16,95 s beside a second, automatic request for the same URL at 15,42 s.
				.contains("data-activity-watch=\"INVENTORY\" data-activity-on-idle=\"hide\"")
				// Each tab watches its own family: a video analysis finishing says nothing
				// about the photo one, and the panel on screen belongs to one of them.
				.contains("'SIMILARITY_VIDEO' : 'SIMILARITY_PHOTO'")
				// The backlog is catalog state rather than one execution's progress, so it has
				// an endpoint of its own instead of a place in the activity snapshot - and, like
				// the others, no longer asks for this whole page to learn four numbers.
				.contains("data-backlog-url=${backlogUrl}")
				// Nothing on this screen refreshes itself by re-rendering the screen.
				.doesNotContain("data-refresh-ms")
				.doesNotContain("window.location.reload()").contains("fingerprint-failures-open")
				.contains("id=\"fingerprintFailuresDialog\"").contains("id=\"fingerprintFailuresRows\"")
				// The dialog is shared by the tabs, so the list it loads is rendered into it.
				// Hard-coding the photo endpoint in the script made the Videos tab show photos.
				.contains("data-failures-url=${failuresUrl}");
		assertThat(javascript).contains("failuresDialog.dataset.failuresUrl").contains("let failuresLoaded = false")
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

	/**
	 * The screen reads what is running; it never asks for itself again to find out.
	 * Re-fetching this page to refresh a status panel is what made a navigation take
	 * seconds under load, because each cycle re-runs the whole render.
	 */
	@Test
	void theActivityPanelsReadTheSharedPollInsteadOfRefetchingThePage() throws Exception {
		String javascript = Files.readString(Path.of("src/main/resources/static/js/pages/duplicates.js"));
		String activity = Files.readString(Path.of("src/main/resources/static/js/execution-activity.js"));

		assertThat(javascript).contains("nimbus-file-manager:activity").contains("data-activity-watch")
				.doesNotContain("fetch(window.location.href)").doesNotContain("fetch(location.href)");

		// Both halves of the snapshot are read: an inventory can be running while the
		// banner draws something else, and a panel that only looked at the primary
		// would call it finished.
		assertThat(javascript).contains("snapshot.primary").contains("snapshot.others");

		// One poll feeds every reader on the page: no second timer, and no second
		// request for the same state.
		// The backlog poll repeats the guarantees the shared one already had.
		assertThat(javascript).contains("if (polling) {").contains("document.hidden ? HIDDEN_MILLIS : wait")
				.contains("panel.dataset.backlogUrl");

		assertThat(activity).contains("nimbus-file-manager:activity").contains("fetch(\"/api/execution-activity\")")
				// The guarantees the shared poll already had, and must keep now that other
				// panels depend on it: one request in flight at a time, and a slower cadence
				// while nobody is looking.
				.contains("if (polling) {").contains("document.hidden ? HIDDEN_MILLIS : wait");
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
				".duplicates-type-filter > summary {", "height: 38px;");
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

	/**
	 * The line that says the other medium is being fingerprinted has to survive the
	 * poll, which means being in the DOM before there is anything to say: the other
	 * fingerprint can start after the page was drawn, and an element rendered
	 * conditionally would never come back.
	 */
	@Test
	void theOtherFingerprintLineIsHiddenRatherThanAbsent() throws Exception {
		String html = Files.readString(Path.of("src/main/resources/templates/app/duplicates.html"));

		// The last one is the point: rendering it conditionally would leave the poll
		// nothing to unhide when the other fingerprint starts.
		assertThat(html).contains("data-backlog-other").contains("th:hidden=\"${phashOther == null}\"")
				.doesNotContain("data-backlog-other th:if");
	}

	/**
	 * And the screen never spells out which fingerprint yields to which: that is
	 * the queue's rule, it is free to change, and a page asserting it would go on
	 * asserting it afterwards. The sentence names what is running, and it comes
	 * from the bundle.
	 */
	@Test
	void theOtherFingerprintLineNamesWhatIsRunningRatherThanWhatIsWaiting() throws Exception {
		String javascript = Files.readString(Path.of("src/main/resources/static/js/pages/duplicates.js"));

		assertThat(javascript).as("the label is built by the server, never assembled here")
				.contains("backlog.other.label").doesNotContain("waiting").doesNotContain("aguardando");

		String messages = Files.readString(Path.of("src/main/resources/messages.properties"));

		assertThat(messages).contains("backend.duplicates.otherFingerprint.photos=")
				.contains("backend.duplicates.otherFingerprint.videos=");
	}

	/**
	 * The highlight has to hold its contrast in both themes, which means colours
	 * that come from the theme rather than from the stylesheet - the mistake the
	 * step progress bar made when it was pinned to one accent and vanished into the
	 * background of the light theme.
	 */
	@Test
	void theOtherFingerprintHighlightTakesItsColoursFromTheTheme() throws Exception {
		String css = Files.readString(Path.of("src/main/resources/static/css/pages/duplicates.css"));

		assertThat(css).contains(".fingerprint-other");

		String block = css.substring(css.indexOf(".fingerprint-other"));

		assertThat(block.substring(0, block.indexOf('}'))).contains("var(--").doesNotContain("#");
	}
}