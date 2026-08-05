package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Keeps the pt-BR (default) and English message bundles in sync so no UI string
 * is left untranslated, and keeps them answering everything the screens ask for.
 */
class MessageBundlesTest {

	/**
	 * A literal key in a Thymeleaf message expression. Keys built from a variable
	 * ({@code #{${…}}}) are deliberately not matched: what they resolve to is only
	 * known at render time.
	 */
	private static final Pattern TEMPLATE_MESSAGE_KEY = Pattern.compile("#\\{([A-Za-z0-9_.\\-]+)");

	@Test
	void bothBundlesExposeExactlyTheSameKeys() throws Exception {
		Properties ptBr = load("/messages.properties");
		Properties english = load("/messages_en.properties");

		Assertions.assertThat(ptBr.stringPropertyNames()).isNotEmpty();
		Assertions.assertThat(english.keySet())
				.as("English bundle must define exactly the same keys as the default pt-BR bundle")
				.isEqualTo(ptBr.keySet());
	}

	/**
	 * Every key a screen asks for is defined.
	 *
	 * <p>
	 * The parity test above keeps the two bundles equal to each other, which says
	 * nothing about whether either answers what the templates ask - and a key
	 * nobody defined does not fail anything at render time. Thymeleaf writes
	 * {@code ??the.missing.key_pt_BR??} into the page and carries on, so the first
	 * to find out is whoever is looking at the screen. That is exactly how one
	 * reached the duplicates page.
	 *
	 * <p>
	 * Only the default bundle is checked, because parity makes the other one a
	 * consequence.
	 */
	@Test
	void everyKeyATemplateAsksForIsDefined() throws Exception {
		Properties ptBr = load("/messages.properties");

		Set<String> missing = new TreeSet<>();

		try (Stream<Path> paths = Files.walk(Path.of("src/main/resources/templates"))) {
			for (Path template : paths.filter(path -> path.toString().endsWith(".html")).toList()) {
				collectMissingKeys(Files.readString(template), ptBr, template, missing);
			}
		}

		Assertions.assertThat(missing).as("These message keys are asked for by a template and defined nowhere."
				+ " Thymeleaf renders them as ??key_pt_BR?? on the page rather than failing, so nothing but a"
				+ " person looking at the screen would notice.").isEmpty();
	}

	private void collectMissingKeys(String html, Properties bundle, Path template, Set<String> missing) {
		Matcher keys = TEMPLATE_MESSAGE_KEY.matcher(html);

		while (keys.find()) {
			if (!bundle.containsKey(keys.group(1))) {
				missing.add(keys.group(1) + " (" + template.getFileName() + ")");
			}
		}
	}

	private Properties load(String resource) throws Exception {
		Properties properties = new Properties();

		try (InputStream input = MessageBundlesTest.class.getResourceAsStream(resource)) {
			Assertions.assertThat(input).as("bundle %s must exist on the classpath", resource).isNotNull();

			properties.load(input);
		}

		return properties;
	}
}