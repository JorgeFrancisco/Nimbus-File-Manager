package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateExclusionFileView;

/** Guards Thymeleaf-parseable expressions on the settings screen. */
class SettingsTemplateTest {

	@Test
	void duplicateFileExclusionPathKeepsTheElvisInsideOneExpression() throws Exception {
		String html = Files.readString(Path.of("src/main/resources/templates/app/settings.html"));

		// The Thymeleaf default operator only accepts another expression (or a literal)
		// on its right side. Closing the "${...}" before "?:" leaves a bare accessor
		// call that is not a valid expression, so the whole attribute fails to parse
		// and the page 500s. The fallback must stay inside a single SpEL expression.
		assertThat(html).contains("th:text=\"${file.currentPath() ?: file.catalogFilePublicId()}\"")
				.doesNotContain("${file.currentPath()} ?:");
	}

	/**
	 * And the names in it are ones the projection really answers to.
	 *
	 * <p>
	 * The half the assertion above cannot see. An accessor named in a template is
	 * text: the compiler never reads it, and the projection was renamed underneath
	 * this one - leaving a fallback that would have thrown the first time a file
	 * exclusion had no path to show, on a screen that shows every other setting
	 * there is.
	 */
	@Test
	void theExclusionRowOnlyNamesAccessorsTheProjectionHas() throws Exception {
		String html = Files.readString(Path.of("src/main/resources/templates/app/settings.html"));

		Set<String> named = Pattern.compile("\\bfile\\.(\\w+)\\(\\)").matcher(html).results()
				.map(result -> result.group(1)).collect(Collectors.toSet());

		Set<String> components = Arrays.stream(DuplicateExclusionFileView.class.getRecordComponents())
				.map(RecordComponent::getName).collect(Collectors.toSet());

		assertThat(named).as("the row reads the projection through these").isNotEmpty().isSubsetOf(components);
	}

	/**
	 * The inventory lock disables the whole Sistema tab, so the screen has to
	 * notice on its own when the inventory ends: before this it stayed blocked
	 * until the user reloaded by hand. The flag on the tab is what the poll
	 * watches.
	 */
	@Test
	void systemTabPublishesTheInventoryLockSoTheScreenUnblocksItself() throws Exception {
		String html = Files.readString(Path.of("src/main/resources/templates/app/settings.html"));
		String javascript = Files.readString(Path.of("src/main/resources/static/js/pages/settings.js"));

		assertThat(html).contains("th:attr=\"data-inventory-running=${inventoryRunning}\"");
		assertThat(javascript).contains("[data-inventory-running]").contains("blockedByInventory()")
				.contains("window.location.reload()");
	}

	/**
	 * Every control of the rebuild form is locked while a rebuild runs. A control
	 * that still looks editable promises a change the running pass will not honour
	 * - it already carries the choices it was started with.
	 */
	@Test
	void everyRebuildControlIsDisabledWhileARebuildRuns() throws Exception {
		String html = Files.readString(Path.of("src/main/resources/templates/app/settings.html"));

		String form = html.substring(html.indexOf("/app/settings/metadata/rebuild}\" method=\"post\""),
				html.indexOf("</form>", html.indexOf("/app/settings/metadata/rebuild}\" method=\"post\"")));

		List<String> unlocked = Arrays.stream(form.split("<")).filter(SettingsTemplateTest::isFormControl)
				.filter(tag -> !tag.contains("th:disabled")).toList();

		assertThat(unlocked).isEmpty();
	}

	private static boolean isFormControl(String tag) {
		if (tag.startsWith("input") && tag.contains("type=\"hidden\"")) {
			return false;
		}

		return tag.startsWith("input") || tag.startsWith("select") || tag.startsWith("button");
	}

	/**
	 * Panels opt into the shared refresh loop by identity, so a new one never needs
	 * its own script - and the geographic one is no longer hardcoded in it.
	 */
	@Test
	void operationPanelsShareOneRefreshLoop() throws Exception {
		String html = Files.readString(Path.of("src/main/resources/templates/app/settings.html"));
		String javascript = Files.readString(Path.of("src/main/resources/static/js/pages/settings.js"));

		assertThat(html).contains("data-operation-panel=\"geo\"").contains("data-operation-panel=\"metadata\"");
		assertThat(javascript).contains("[data-operation-panel]").doesNotContain(".geo-admin-panel");
	}
}