package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Guards the server-rendered message catalog used by browser-side UI updates.
 */
class JavaScriptI18nTest {

	private static final String CATALOG = "src/main/resources/templates/fragments/i18n-messages.html";

	private static final Pattern KEY = Pattern.compile("[\"'](js[.][A-Za-z0-9_.]*)[\"']");

	@Test
	void layoutLoadsCatalogAndTranslatorBeforeApplicationScripts() throws Exception {
		String layout = read("src/main/resources/templates/fragments/layout.html");

		assertThat(layout).containsSubsequence("fragments/i18n-messages :: catalog", "@{/js/i18n.js}", "@{/js/app.js}",
				"@{/js/execution-status.js}");
	}

	@Test
	void translatorSupportsNumberedMessageParameters() throws Exception {
		String javascript = read("src/main/resources/static/js/i18n.js");

		assertThat(javascript).contains("window.NimbusFileManagerMessageCatalog", "window.NimbusFileManagerI18n",
				"replace(/\\{(\\d+)\\}/g");
	}

	@Test
	void dynamicScreensReadVisibleCopyFromTranslator() throws Exception {
		String duplicates = read("src/main/resources/static/js/pages/duplicates.js");
		String quarantine = read("src/main/resources/static/js/pages/quarantine.js");
		String timeline = read("src/main/resources/static/js/pages/timeline.js");
		String progress = read("src/main/resources/static/js/pages/execution-progress.js");

		assertThat(duplicates).contains("window.NimbusFileManagerI18n.t", "js.duplicates.movingProgress");
		assertThat(quarantine).contains("window.NimbusFileManagerI18n.t", "js.quarantine.purgeCompleted");
		assertThat(timeline).contains("new Intl.DateTimeFormat(i18n.locale", "js.timeline.loadingLibrary");
		assertThat(progress).contains("window.NimbusFileManagerI18n.t", "js.execution.cancelConfirm");
	}

	@Test
	void inlineConfirmationsReceiveLocalizedDataFromThymeleaf() throws Exception {
		String settings = read("src/main/resources/templates/app/settings.html");
		String duplicates = read("src/main/resources/templates/app/duplicates.html");

		assertThat(settings).contains("#{settings.geo.removeConfirm}", "#{settings.exec.cleanupAgeConfirm}",
				"#{settings.exec.cleanupKeepConfirm}");
		// The rebuild confirm is a Thymeleaf message expression, per-tab (photo/video).
		assertThat(duplicates).contains("th:data-confirm=", "#{duplicates.rebuild.confirm}",
				"#{duplicates.rebuild.confirm.videos}");
		assertThat(settings + duplicates).doesNotContain("return confirm('");
	}

	@Test
	void quarantineSelectionSurvivesPageNavigation() throws Exception {
		String quarantine = read("src/main/resources/static/js/pages/quarantine.js");

		assertThat(quarantine).contains("nimbus-file-manager.quarantine.selection.v1",
				"window.localStorage.getItem(selectionStorageKey)", "window.localStorage.setItem(selectionStorageKey",
				"return Object.keys(selection)");
	}

	/**
	 * The dialog that explains why nothing was deleted used to be wiped by the
	 * refresh that followed it - 700 milliseconds, enough to see something appear
	 * and not to read it. The refresh now waits for the dialog to be dismissed,
	 * whether by the button, Esc or the backdrop.
	 */
	@Test
	void theQuarantineReasonSurvivesUntilItIsDismissed() throws Exception {
		String quarantine = read("src/main/resources/static/js/pages/quarantine.js");

		assertThat(quarantine).contains("showOutcome(result.message, reloadSoon)")
				.contains("dialog.addEventListener(\"close\"")
				.doesNotContain("showOutcome(result.message);");
	}

	/**
	 * The catalog is a hand-written list, so a key added to the bundles but not to
	 * it resolves to its own name and the user reads "js.backgroundJob.count" on
	 * the screen - which is exactly what shipped once. A key ending in a dot is a
	 * prefix the script completes at runtime, and matches any catalog key under it.
	 */
	@Test
	void everyKeyTheScriptsAskForExistsInTheCatalog() throws Exception {
		Set<String> registered = keysIn(read(CATALOG));

		List<String> missing = new ArrayList<>();

		for (Path script : scripts()) {
			for (String key : keysIn(Files.readString(script))) {
				if (!isKnown(key, registered)) {
					missing.add(key + " (" + script.getFileName() + ")");
				}
			}
		}

		assertThat(missing).isEmpty();
	}

	private static boolean isKnown(String key, Set<String> registered) {
		return key.endsWith(".") ? registered.stream().anyMatch(known -> known.startsWith(key))
				: registered.contains(key);
	}

	private static Set<String> keysIn(String source) {
		Set<String> keys = new LinkedHashSet<>();
		Matcher matcher = KEY.matcher(source);

		while (matcher.find()) {
			keys.add(matcher.group(1));
		}

		return keys;
	}

	private static List<Path> scripts() throws Exception {
		try (Stream<Path> files = Files.walk(Path.of("src/main/resources/static/js"))) {
			return files.filter(path -> path.toString().endsWith(".js")).toList();
		}
	}

	private String read(String path) throws Exception {
		return Files.readString(Path.of(path));
	}
}