package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** Guards i18n coverage in shared fragments and the standalone error page. */
class SharedTemplatesI18nTest {

	private static final Pattern MESSAGE_NESTED_IN_VARIABLE = Pattern.compile("\\$\\{[^}\"]*#\\{");

	@Test
	void settingsRowUsesMessagesForControlsAndMetadata() throws Exception {
		String html = read("src/main/resources/templates/fragments/settings-row.html");

		assertThat(html).contains("#{common.yes}", "#{common.no}", "#{quarantine.origin.chooseFolder}",
				"#{action.save}", "#{settings.updatedByAt(", "#messages.msg('setting.' + setting.settingKey)");
	}

	@Test
	void folderPickerUsesMessagesForAllVisibleStaticCopy() throws Exception {
		String html = read("src/main/resources/templates/fragments/folder-picker.html");

		assertThat(html).contains("#{folderPicker.title}", "#{folderPicker.description}", "#{folderPicker.close}",
				"#{folderPicker.thisComputer}", "#{folderPicker.up}", "#{folderPicker.choose}");
	}

	@Test
	void standaloneErrorPageUsesLocalizedStatusMessages() throws Exception {
		String html = read("src/main/resources/templates/error.html");

		assertThat(html).contains("#{error.title}", "#messages.msg('error.title')", "#{error.403}", "#{error.404}",
				"#{error.401}", "#{error.generic}", "#{error.retryLater}", "#{error.home}");
	}

	@Test
	void activeExecutionUsesMessageExpressionsOutsideSpelExpressions() throws Exception {
		String html = read("src/main/resources/templates/fragments/layout.html");

		// The running execution names itself with the label the back end resolved: the
		// banner used to decide between "inventory" and "organization" in the template,
		// so a conversion announced itself as an organization.
		assertThat(html).contains("#{execution.running(${activeExecution.typeLabel()})}")
				.contains("${activeExecution.percentComplete() != null} ? "
						+ "${#numbers.formatPercent(activeExecution.percentComplete() / 100.0, 1, 2)} "
						+ ": #{execution.preparing}")
				.doesNotContain("execution.organization.running").doesNotContain("+ '%' : #{execution.preparing}}");
	}

	@Test
	void noTemplateNestsAThymeleafMessageInsideASpelVariableExpression() throws Exception {
		Path templatesRoot = Path.of("src/main/resources/templates");

		List<Path> invalidTemplates;

		try (Stream<Path> templates = Files.walk(templatesRoot)) {
			invalidTemplates = templates.filter(path -> path.toString().endsWith(".html"))
					.filter(this::containsNestedMessage).map(templatesRoot::relativize).toList();
		}

		assertThat(invalidTemplates)
				.as("Thymeleaf message expressions must not be nested inside ${...} SpEL expressions").isEmpty();
	}

	private boolean containsNestedMessage(Path template) {
		try {
			return MESSAGE_NESTED_IN_VARIABLE.matcher(Files.readString(template)).find();
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}

	private String read(String path) throws Exception {
		return Files.readString(Path.of(path));
	}
}