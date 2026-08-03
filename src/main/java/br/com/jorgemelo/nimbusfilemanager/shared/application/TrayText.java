package br.com.jorgemelo.nimbusfilemanager.shared.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

/**
 * The words on the tray menu, and the address its first item opens.
 *
 * <p>
 * Read straight from the message bundles rather than through the
 * {@code MessageSource}: the icon appears before Spring does - which is the
 * whole point of it, since the first start spends minutes downloading a
 * database server before any context exists - and there is nothing to ask at
 * that moment. The bundles are the same files, so the text stays in one place
 * either way.
 *
 * <p>
 * The language follows the machine, not a request: nothing here belongs to a
 * session, and a tray icon outlives every one of them.
 */
public final class TrayText {

	private static final String BASE_BUNDLE = "/messages.properties";
	private static final String ENGLISH_BUNDLE = "/messages_en.properties";
	private static final String PORTUGUESE = "pt";

	private final Properties bundle = new Properties();

	public TrayText(Locale locale) {
		this(PORTUGUESE.equals(locale.getLanguage()) ? BASE_BUNDLE : ENGLISH_BUNDLE);
	}

	/**
	 * Naming the file is what makes "the packaged jar lost a bundle" a testable
	 * outcome rather than a guess: the two callers above always name one that is
	 * there.
	 */
	TrayText(String resource) {
		load(resource);
	}

	/**
	 * @throws IllegalStateException when the key is absent, matching what the
	 * {@code MessageSource} does elsewhere: a missing key is a build mistake, and
	 * a label reading {@code tray.exit} on somebody's desktop is worse than a
	 * failure that says so.
	 */
	public String get(String key) {
		String text = bundle.getProperty(key);

		if (text == null) {
			throw new IllegalStateException("No message for tray key " + key);
		}

		return text;
	}

	/**
	 * Always the loopback address. The server binds elsewhere too, but this menu
	 * belongs to whoever is sitting at the machine, and the name that always
	 * resolves for them is this one.
	 */
	public static String url(int port) {
		return "http://localhost:" + port + "/";
	}

	private void load(String resource) {
		try (InputStream stream = TrayText.class.getResourceAsStream(resource)) {
			if (stream == null) {
				throw new IllegalStateException("Missing message bundle " + resource);
			}

			try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
				bundle.load(reader);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read " + resource, e);
		}
	}
}