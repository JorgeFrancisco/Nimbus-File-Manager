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
 * Guards the Thymeleaf templates against the two failures no other test sees:
 * the controller tests assert the model and the view name, never the rendering,
 * so a template that lost its content or that reads a model attribute nobody
 * publishes still passes them - and the screen only goes blank in production.
 */
class TemplateIntegrityTest {

	private static final Path TEMPLATES = Path.of("src/main/resources/templates");

	private static final Path CONTROLLERS = Path.of("src/main/java");

	private static final Pattern LABEL_MAP = Pattern.compile("\\b([a-zA-Z]+Labels)\\.");

	private static final Pattern PUBLISHED = Pattern.compile("addAttribute\\(\"([a-zA-Z]+Labels)\"");

	/**
	 * A whole screen shipped blank because tooling truncated its template to zero
	 * bytes and the file kept its name, so nothing downstream noticed.
	 */
	@Test
	void noTemplateIsEmpty() throws Exception {
		List<String> empty = new ArrayList<>();

		for (Path template : templates()) {
			if (Files.readString(template).isBlank()) {
				empty.add(template.toString());
			}
		}

		assertThat(empty).isEmpty();
	}

	/**
	 * Renaming a label map in the controller leaves the template reading the old
	 * name, which Thymeleaf only complains about when someone opens the page.
	 */
	@Test
	void everyLabelMapATemplateReadsIsPublishedByAController() throws Exception {
		Set<String> published = published();

		List<String> missing = new ArrayList<>();

		for (Path template : templates()) {
			for (String labelMap : namesIn(LABEL_MAP, Files.readString(template))) {
				if (!published.contains(labelMap)) {
					missing.add(labelMap + " (" + template.getFileName() + ")");
				}
			}
		}

		assertThat(missing).isEmpty();
	}

	private static Set<String> published() throws Exception {
		try (Stream<Path> files = Files.walk(CONTROLLERS)) {
			Set<String> names = new LinkedHashSet<>();

			for (Path source : files.filter(path -> path.toString().endsWith("Controller.java")).toList()) {
				names.addAll(namesIn(PUBLISHED, Files.readString(source)));
			}

			return names;
		}
	}

	private static Set<String> namesIn(Pattern pattern, String source) {
		Set<String> names = new LinkedHashSet<>();
		Matcher matcher = pattern.matcher(source);

		while (matcher.find()) {
			names.add(matcher.group(1));
		}

		return names;
	}

	private static List<Path> templates() throws Exception {
		try (Stream<Path> files = Files.walk(TEMPLATES)) {
			return files.filter(path -> path.toString().endsWith(".html")).toList();
		}
	}
}