package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Guards Thymeleaf-parseable expressions on the settings screen. */
class SettingsTemplateTest {

	@Test
	void duplicateFileExclusionPathKeepsTheElvisInsideOneExpression() throws Exception {
		String html = Files.readString(Path.of("src/main/resources/templates/app/settings.html"));

		// The Thymeleaf default operator only accepts another expression (or a literal)
		// on its right side. Closing the "${...}" before "?:" leaves a bare
		// "file.publicId()" that is not a valid expression, so the whole attribute
		// fails to parse and the page 500s. The fallback must stay inside a single SpEL
		// expression: "${file.currentPath() ?: file.publicId()}".
		assertThat(html).contains("th:text=\"${file.currentPath() ?: file.publicId()}\"")
				.doesNotContain("${file.currentPath()} ?: file.publicId()");
	}

	/**
	 * The inventory lock disables the whole Sistema tab, so the screen has to notice
	 * on its own when the inventory ends: before this it stayed blocked until the
	 * user reloaded by hand. The flag on the tab is what the poll watches.
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