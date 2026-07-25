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
}