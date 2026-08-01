package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Every class a template asks for has to exist in a stylesheet.
 *
 * <p>
 * Rendering tests cannot catch this. They assert on the HTML the server
 * produced and never load a stylesheet, so a class that no rule defines renders
 * perfectly and looks broken only in a browser - which is how a table of
 * actions shipped with its buttons stacked on top of each other, styled by
 * nothing at all.
 *
 * <p>
 * A real browser would catch more, and cost minutes per run. This costs
 * milliseconds and catches the one mistake that actually happened: a name
 * written in a template and never written in the CSS.
 */
class TemplateStyleReferenceTest {

	private static final Path TEMPLATES = Path.of("src/main/resources/templates");
	private static final Path STYLES = Path.of("src/main/resources/static/css");
	private static final Path SCRIPTS = Path.of("src/main/resources/static");

	private static final Pattern CLASS_ATTRIBUTE = Pattern.compile("class=\"([^\"$]*)\"");
	private static final Pattern SELECTOR = Pattern.compile("\\.([a-zA-Z][\\w-]*)");

	/**
	 * Names that belong to somebody else's stylesheet or to no stylesheet at all:
	 * the icon font, and the hooks that only JavaScript and the tests look for.
	 */
	private static final Set<String> EXTERNAL = Set.of("bi", "collapsed", "active", "open", "selected", "hidden",
			"dragging", "drop-target", "is-invalid", "show");

	@Test
	void everyClassUsedByATemplateIsDefinedInTheStylesheets() throws IOException {
		Set<String> defined = names(STYLES, ".css", SELECTOR);

		// A class earns its place by being styled or by being found from script. The
		// second half is read from the sources rather than listed here: a fixed list
		// of exceptions goes stale and starts hiding what this exists to find.
		String scripts = read(SCRIPTS, ".js");

		Set<String> missing = new TreeSet<>();

		for (Path template : files(TEMPLATES, ".html")) {
			Matcher matcher = CLASS_ATTRIBUTE.matcher(Files.readString(template, StandardCharsets.UTF_8));

			while (matcher.find()) {
				for (String name : matcher.group(1).trim().split("\\s+")) {
					if (!name.isBlank() && !name.startsWith("bi-") && !name.startsWith("js-") && !EXTERNAL.contains(name)
							&& !defined.contains(name) && !scripts.contains(name)) {
						missing.add(name + " (" + template.getFileName() + ")");
					}
				}
			}
		}

		Assertions.assertThat(missing).as("classes used by a template and defined by no stylesheet").isEmpty();
	}

	private String read(Path folder, String extension) throws IOException {
		StringBuilder all = new StringBuilder();

		for (Path file : files(folder, extension)) {
			all.append(Files.readString(file, StandardCharsets.UTF_8));
		}

		return all.toString();
	}

	private Set<String> names(Path folder, String extension, Pattern pattern) throws IOException {
		Set<String> found = new TreeSet<>();

		for (Path file : files(folder, extension)) {
			Matcher matcher = pattern.matcher(Files.readString(file, StandardCharsets.UTF_8));

			while (matcher.find()) {
				found.add(matcher.group(1));
			}
		}

		return found;
	}

	private Iterable<Path> files(Path folder, String extension) throws IOException {
		try (Stream<Path> walk = Files.walk(folder)) {
			return walk.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(extension)).toList();
		}
	}
}